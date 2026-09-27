use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use libmathcat::interface::*;
use std::cell::RefCell;
use std::panic;
use std::sync::Mutex;

thread_local! {
    static IS_THREAD_INITIALIZED: RefCell<bool> = RefCell::new(false);
}

static MATHCAT: Mutex<()> = Mutex::new(());

fn init_for_current_thread(rules_dir: &str) {
    IS_THREAD_INITIALIZED.with(|initialized| {
        let mut init = initialized.borrow_mut();
        if !*init {
            let _ = set_rules_dir(rules_dir.to_string());
            let _ = set_preference("SpeechStyle".to_string(), "SimpleSpeak".to_string());
            *init = true;
        }
    });
}

fn apply_language(locale: &str) {
    let language = if locale.trim().is_empty() {
        "en".to_string()
    } else {
        locale.to_string()
    };
    let _ = set_preference("Language".to_string(), language);
}

fn json_escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 8);
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

fn read_string(env: &mut JNIEnv, value: &JString) -> String {
    match env.get_string(value) {
        Ok(text) => text.into(),
        Err(_) => String::new(),
    }
}

fn prepare(env: &mut JNIEnv, rules_dir_path: &JString, locale: &JString) -> String {
    let rules_dir = read_string(env, rules_dir_path);
    if !rules_dir.is_empty() {
        init_for_current_thread(&rules_dir);
    }
    let locale_tag = read_string(env, locale);
    apply_language(&locale_tag);
    locale_tag
}

fn lock_mathcat() -> std::sync::MutexGuard<'static, ()> {
    match MATHCAT.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn explain(error: &libmathcat::errors::Error) -> String {
    error
        .iter()
        .map(|cause| cause.to_string())
        .collect::<Vec<_>>()
        .join("\n")
}

fn speak_stage(detail: &str) -> &'static str {
    let lower = detail.to_lowercase();
    if lower.contains("intent") && !lower.contains("pattern match") && !lower.contains("unknown function") {
        "intent"
    } else {
        "speech"
    }
}

fn failure(stage: &str, detail: &str, language: &str) -> String {
    format!(
        "{{\"ok\":false,\"stage\":\"{}\",\"error\":\"{}\",\"version\":\"{}\",\"language\":\"{}\"}}",
        stage,
        json_escape(detail),
        json_escape(&get_version()),
        json_escape(language),
    )
}

fn return_string(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(text) => text.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_readium_r2_shared_publication_services_content_iterators_MathSpeechEngine_mathCatToSpeech(
    mut env: JNIEnv,
    _class: JClass,
    input_mathml: JString,
    rules_dir_path: JString,
    locale: JString,
) -> jstring {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let _guard = lock_mathcat();
        prepare(&mut env, &rules_dir_path, &locale);
        let _ = set_preference("TTS".to_string(), "None".to_string());
        let _ = set_preference("Bookmark".to_string(), "false".to_string());
        let mathml = read_string(&mut env, &input_mathml);
        if mathml.trim().is_empty() || set_mathml(mathml).is_err() {
            return String::new();
        }
        get_spoken_text().unwrap_or_default()
    }));
    return_string(&mut env, result.unwrap_or_default())
}

#[no_mangle]
pub extern "system" fn Java_org_readium_r2_shared_publication_services_content_iterators_MathSpeechEngine_mathCatBundle(
    mut env: JNIEnv,
    _class: JClass,
    input_mathml: JString,
    rules_dir_path: JString,
    locale: JString,
) -> jstring {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let _guard = lock_mathcat();
        let language = prepare(&mut env, &rules_dir_path, &locale);
        let _ = set_preference("TTS".to_string(), "SSML".to_string());
        let _ = set_preference("Bookmark".to_string(), "true".to_string());
        let mathml = read_string(&mut env, &input_mathml);
        if mathml.trim().is_empty() {
            return String::new();
        }
        let canonical = match set_mathml(mathml) {
            Ok(value) => value,
            Err(error) => return failure("canonicalize", &explain(&error), &language),
        };
        let markup = match get_spoken_text() {
            Ok(value) if !value.trim().is_empty() => value,
            Ok(_) => return failure("speech", "empty speech", &language),
            Err(error) => {
                let detail = explain(&error);
                return failure(speak_stage(&detail), &detail, &language);
            }
        };
        let version = format!("{}|SimpleSpeak|{}", get_version(), language);
        format!(
            "{{\"canonical\":\"{}\",\"markup\":\"{}\",\"version\":\"{}\"}}",
            json_escape(&canonical),
            json_escape(&markup),
            json_escape(&version),
        )
    }));
    return_string(&mut env, result.unwrap_or_default())
}
