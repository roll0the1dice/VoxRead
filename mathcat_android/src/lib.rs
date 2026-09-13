use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use libmathcat::interface::*;
use std::panic;
use std::cell::RefCell;

thread_local! {
    static IS_THREAD_INITIALIZED: RefCell<bool> = RefCell::new(false);
}

fn init_for_current_thread(rules_dir: &str) {
    IS_THREAD_INITIALIZED.with(|initialized| {
        let mut init = initialized.borrow_mut();
        if !*init {
            let _ = set_rules_dir(rules_dir.to_string());
            // 锁定为中文与 SimpleSpeak
            let _ = set_preference("Language".to_string(), "zh-tw".to_string());
            let _ = set_preference("SpeechStyle".to_string(), "SimpleSpeak".to_string());
            let _ = set_preference("TTS".to_string(), "None".to_string());
            *init = true;
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_org_readium_r2_shared_publication_services_content_iterators_MathSpeechEngine_mathCatToSpeech(
    mut env: JNIEnv,
    _class: JClass,
    input_mathml: JString,
    rules_dir_path: JString,
) -> jstring {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let rules_dir: String = match env.get_string(&rules_dir_path) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        if !rules_dir.is_empty() {
            init_for_current_thread(&rules_dir);
        }

        let mathml: String = match env.get_string(&input_mathml) {
            Ok(s) => s.into(),
            Err(_) => return String::new(),
        };

        if mathml.trim().is_empty() {
            return String::new();
        }

        // 1. 设置 MathML，出错返回空字符串（交给 Kotlin 降级处理）
        if set_mathml(mathml).is_err() {
            return String::new();
        }

        // 2. 获取语音文本，出错返回空字符串
        match get_spoken_text() {
            Ok(text) => text,
            Err(_) => String::new(),
        }
    }));

    let out_str = match result {
        Ok(speech) => speech,
        Err(_) => String::new(),
    };

    match env.new_string(out_str) {
        Ok(j_str) => j_str.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}