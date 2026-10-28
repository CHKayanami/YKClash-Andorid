use clash_lib::Config;
use jni::{JNIEnv, objects::{JObject, JString}, sys::jstring};
use serde_yaml::{Mapping, Value};

pub fn default_dns2(has_cn_provider: bool) -> Value {
    let mut requests = Vec::new();
    if has_cn_provider {
        requests.push(serde_json::json!({"rule-set": ["geosite-cn"], "upstream": "direct-dns"}));
    }
    requests.push(serde_json::json!({"fallback": "proxy-dns"}));
    serde_yaml::to_value(serde_json::json!({
        "enable": true, "ipv6": true, "use-hosts": true,
        "default-nameserver": ["223.5.5.5"], "proxy-server-nameserver": [],
        "optimistic-cache-ttl": 0, "stale-cache-retention": 3600, "cache-capacity": 4096,
        "upstreams": [
            {"tag": "direct-dns", "type": "remote", "servers": ["223.5.5.5"]},
            {"tag": "proxy-dns", "type": "fakeip", "inet4-range": "198.18.0.1/16", "inet6-range": "fc00::/18", "ttl": 1}
        ],
        "routing": {"request": requests, "response": []}, "hosts": {}, "hosts-files": []
    })).unwrap()
}

pub fn ensure_dns2(config: &mut Mapping) {
    let key = Value::String("dns2".into());
    let existing = config.get(&key).and_then(Value::as_mapping);
    if existing.and_then(|dns| dns.get(Value::String("enable".into()))).and_then(Value::as_bool) == Some(true) {
        return;
    }
    let has_cn = config.get(Value::String("rule-providers".into()))
        .and_then(Value::as_mapping).is_some_and(|providers| providers.contains_key(Value::String("geosite-cn".into())));
    let mut dns = default_dns2(has_cn);
    if let Some(existing) = existing.filter(|dns| dns.get(Value::String("upstreams".into())).and_then(Value::as_sequence).is_some_and(|list| !list.is_empty())) {
        dns = Value::Mapping(existing.clone());
        dns.as_mapping_mut().unwrap().insert(Value::String("enable".into()), Value::Bool(true));
    }
    config.insert(key, dns);
}

fn document(raw: &str) -> Result<String, String> {
    let mut config: Mapping = serde_yaml::from_str(raw).map_err(|e| e.to_string())?;
    ensure_dns2(&mut config);
    serde_json::to_string(&config).map_err(|e| e.to_string())
}

fn encode(json: &str) -> Result<String, String> {
    let json: serde_json::Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
    if !json.is_object() { return Err("配置必须为映射".into()); }
    let yaml = serde_yaml::to_string(&json).map_err(|e| e.to_string())?;
    // Validate the effective mobile config, including DNS2 upstream references,
    // while leaving profile-only fields and desktop TUN options intact on disk.
    let effective = super::prepare(&yaml, None, "validation")?;
    Config::Str(effective).try_parse().map_err(|e| e.to_string())?;
    Ok(yaml)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_configDocument(
    mut env: JNIEnv, _this: JObject, raw: JString,
) -> jstring {
    let result = (|| {
        let raw: String = env.get_string(&raw).map_err(|e| e.to_string())?.into();
        document(&raw)
    })();
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_encodeConfig(
    mut env: JNIEnv, _this: JObject, json: JString,
) -> jstring {
    let result = (|| {
        let json: String = env.get_string(&json).map_err(|e| e.to_string())?.into();
        encode(&json)
    })();
    respond(&mut env, result)
}

fn respond(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(value) => env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); std::ptr::null_mut() }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_previewConfig(
    mut env: JNIEnv, _this: JObject, raw: JString, mode: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let raw: String = env.get_string(&raw).map_err(|e| e.to_string())?.into();
        let mode: String = env.get_string(&mode).map_err(|e| e.to_string())?.into();
        let network: serde_json::Value = serde_json::from_str(&super::network_config(&raw, &mode)?).map_err(|e| e.to_string())?;
        let yaml = super::prepare(network["config"].as_str().ok_or("配置解析失败")?, Some(0), "<每次连接时自动生成>")?;
        Ok(format!("# 待连接预览：fd://0 是待分配的 Android VPN 文件描述符占位值。\n{yaml}"))
    })();
    respond(&mut env, result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visual_roundtrip_preserves_unknown_fields_and_sequence() {
        let raw = "custom-section: {opaque: [a, b]}\nproxy-groups: [{name: First, type: select, proxies: [DIRECT]}, {name: Second, type: select, proxies: [First]}]\nrules: ['MATCH,Second']\n";
        let json = document(raw).unwrap();
        let output = encode(&json).unwrap();
        let value: Value = serde_yaml::from_str(&output).unwrap();
        assert_eq!(value["custom-section"]["opaque"][1], "b");
        assert_eq!(value["proxy-groups"][0]["name"], "First");
        assert_eq!(value["dns2"]["upstreams"][1]["type"], "fakeip");
    }

    #[test]
    fn dns2_defaults_reference_cn_only_when_provider_exists_and_reject_invalid_upstream() {
        let json = document("rule-providers: {geosite-cn: {type: inline, behavior: domain, payload: [example.cn]}}\nrules: ['MATCH,DIRECT']\n").unwrap();
        let output = encode(&json).unwrap();
        let value: Value = serde_yaml::from_str(&output).unwrap();
        assert_eq!(value["dns2"]["routing"]["request"][0]["rule-set"][0], "geosite-cn");
        let mut json: serde_json::Value = serde_json::from_str(&json).unwrap();
        json["dns2"]["routing"]["request"][1]["fallback"] = "nonexistent".into();
        assert!(encode(&json.to_string()).is_err());
    }
}

// Generic mapping conversion for node options: no DNS defaults or core validation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_convertYamlMapping(
    mut env: JNIEnv, _this: JObject, raw: JString, to_yaml: jni::sys::jboolean,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let raw: String = env.get_string(&raw).map_err(|e| e.to_string())?.into();
        if to_yaml != 0 {
            let value: serde_json::Value = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
            if !value.is_object() { return Err("高级参数必须为 YAML 映射".into()); }
            serde_yaml::to_string(&value).map_err(|e| e.to_string())
        } else {
            let value: Mapping = serde_yaml::from_str(&raw).map_err(|e| e.to_string())?;
            serde_json::to_string(&value).map_err(|e| e.to_string())
        }
    })();
    respond(&mut env, result)
}
