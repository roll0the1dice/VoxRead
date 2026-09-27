use libmathcat::interface::{get_spoken_text, get_version, set_mathml, set_preference, set_rules_dir};

fn rules_dir() -> String {
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../readium/shared/src/main/assets/mathcat_rules")
        .canonicalize()
        .expect("rules")
        .to_string_lossy()
        .into_owned()
}

fn speak(mathml: &str, language: &str, tts: &str) -> Result<(String, String), String> {
    let _ = set_rules_dir(rules_dir());
    let _ = set_preference("Language".to_string(), language.to_string());
    let _ = set_preference("SpeechStyle".to_string(), "SimpleSpeak".to_string());
    let _ = set_preference("TTS".to_string(), tts.to_string());
    let _ = set_preference("Bookmark".to_string(), if tts == "SSML" { "true" } else { "false" }.to_string());
    let canonical = set_mathml(mathml.to_string()).map_err(|error| error.to_string())?;
    let speech = get_spoken_text().map_err(|error| error.to_string())?;
    Ok((canonical, speech))
}

fn mark_ids(speech: &str) -> Vec<String> {
    let mut ids = Vec::new();
    let mut rest = speech;
    while let Some(start) = rest.find("<mark name='") {
        let after = &rest[start + "<mark name='".len()..];
        let end = after.find('\'').expect("mark");
        ids.push(after[..end].to_string());
        rest = &after[end..];
    }
    ids
}

#[test]
fn probe_bookmarks() {
    println!("version {}", get_version());
    let mut checked = 0;
    let samples = [
        (
            "power",
            r#"<math xmlns="http://www.w3.org/1998/Math/MathML"><mrow><msup><mi>x</mi><mn>2</mn></msup><mo>+</mo><mi>y</mi></mrow></math>"#,
        ),
        (
            "fraction",
            r#"<math xmlns="http://www.w3.org/1998/Math/MathML"><mfrac><mi>a</mi><mi>b</mi></mfrac></math>"#,
        ),
        (
            "root",
            r#"<math xmlns="http://www.w3.org/1998/Math/MathML"><msqrt><mi>x</mi></msqrt></math>"#,
        ),
        (
            "matrix",
            r#"<math xmlns="http://www.w3.org/1998/Math/MathML"><mrow><mo>[</mo><mtable><mtr><mtd><mi>a</mi></mtd><mtd><mi>b</mi></mtd></mtr><mtr><mtd><mi>c</mi></mtd><mtd><mi>d</mi></mtd></mtr></mtable><mo>]</mo></mrow></math>"#,
        ),
        (
            "repeat",
            r#"<math xmlns="http://www.w3.org/1998/Math/MathML"><mrow><mi>x</mi><mo>+</mo><mi>x</mi></mrow></math>"#,
        ),
    ];
    for language in ["en", "zh-tw"] {
        for (name, mathml) in samples {
            println!("\n== {language} {name} ==");
            match speak(mathml, language, "SSML") {
                Ok((canonical, speech)) => {
                    let ids = mark_ids(&speech);
                    assert!(!ids.is_empty(), "{language} {name} produced no bookmarks");
                    for id in &ids {
                        assert!(
                            canonical.contains(&format!("id='{id}'")),
                            "{language} {name} bookmark {id} is missing from the canonical formula"
                        );
                    }
                    if name == "repeat" {
                        assert!(ids.len() >= 3, "{ids:?}");
                        assert_ne!(ids.first(), ids.last(), "repeated variables share one node");
                    }
                    checked += 1;
                    let ids: String = canonical
                        .split("id='")
                        .skip(1)
                        .filter_map(|part| part.split('\'').next().map(|id| {
                            let start = canonical.find(&format!("id='{id}'")).unwrap_or(0);
                            let window = &canonical[start.saturating_sub(24)..start];
                            let tag = window.rsplit('<').next().unwrap_or("?");
                            format!("{tag}#{id}")
                        }))
                        .collect::<Vec<_>>()
                        .join(" ");
                    println!("IDS {ids}");
                    println!("SPEECH {speech:?}");
                }
                Err(error) => println!("SSML FAIL {}", error.lines().next().unwrap_or("")),
            }
            match speak(mathml, language, "None") {
                Ok((_, speech)) => println!("PLAIN {speech}"),
                Err(error) => println!("PLAIN FAIL {error}"),
            }
        }
    }
    assert!(checked >= 8, "expected bookmarks for the sample formulas, got {checked}");
}

fn speak_chain(mathml: &str, language: &str) -> Result<(String, String), String> {
    let _ = set_rules_dir(rules_dir());
    let _ = set_preference("Language".to_string(), language.to_string());
    let _ = set_preference("SpeechStyle".to_string(), "SimpleSpeak".to_string());
    let _ = set_preference("TTS".to_string(), "SSML".to_string());
    let _ = set_preference("Bookmark".to_string(), "true".to_string());
    let canonical = set_mathml(mathml.to_string()).map_err(|error| format!("canonicalize\n{error:?}"))?;
    let speech = get_spoken_text().map_err(|error| format!("speak\n{error:?}"))?;
    Ok((canonical, speech))
}

fn plain_with_marks(speech: &str) -> (String, Vec<(String, usize)>) {
    let mut text = String::new();
    let mut marks = Vec::new();
    let mut rest = speech;
    while let Some(start) = rest.find('<') {
        text.push_str(&rest[..start]);
        let after = &rest[start..];
        let close = after.find('>').expect("tag");
        let tag = &after[..=close];
        if let Some(name_at) = tag.find("name='") {
            let id_start = name_at + "name='".len();
            let id_end = tag[id_start..].find('\'').expect("id");
            marks.push((tag[id_start..id_start + id_end].to_string(), text.len()));
        }
        rest = &after[close + 1..];
    }
    text.push_str(rest);
    (text, marks)
}

#[test]
fn display_rate_speaks_with_bookmarks() {
    let full = include_str!("fixtures/display_rate.mathml");
    let (canonical, speech) = speak_chain(full, "zh-tw").unwrap_or_else(|error| panic!("{error}"));
    let (text, marks) = plain_with_marks(&speech);
    assert!(!marks.is_empty(), "{speech}");
    for (id, _) in &marks {
        let present = id.strip_suffix("-indexed-by").unwrap_or(id.as_str());
        assert!(
            canonical.contains(&format!("id='{id}'")) || canonical.contains(&format!("id='{present}'")),
            "{id} missing from canonical"
        );
    }
    let leaked = ["qquad", "asymp", "longrightarrow", "\\"];
    for token in leaked {
        assert!(!text.contains(token), "latex leaked in {text}");
    }
    assert!(text.contains('ℓ') || text.contains("ell") || text.contains("分之"), "{text}");
}

#[test]
fn subscript_word_uses_the_structure_node() {
    let mathml = r#"<math xmlns="http://www.w3.org/1998/Math/MathML"><msub><mi>L</mi><mi>t</mi></msub></math>"#;
    let (canonical, speech) = speak_chain(mathml, "zh-tw").unwrap_or_else(|error| panic!("{error}"));
    let (text, marks) = plain_with_marks(&speech);
    let word = text.find("下標").or_else(|| text.find("下标")).expect(&text);
    let structure = marks.iter().rev().find(|(_, at)| *at <= word).expect("bookmark before 下标");
    let letter = marks.iter().find(|(_, at)| *at > word).expect("bookmark after 下标");
    assert_ne!(structure.0, letter.0, "下标 stayed on the following leaf: {speech}");
    assert!(canonical.contains(&format!("id='{}'", structure.0)), "{}", structure.0);
    let structure_at = canonical.find(&format!("id='{}'", structure.0)).unwrap();
    let window = &canonical[structure_at.saturating_sub(80)..structure_at];
    assert!(
        window.contains("msub") || window.contains("indexed-by"),
        "structure bookmark is not the subscript: {window}"
    );
}
