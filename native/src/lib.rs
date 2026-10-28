use clash_lib::{Config, start};
use jni::{JNIEnv, objects::{JObject, JString}, sys::{jboolean, jint, jstring}};
use serde_yaml::{Mapping, Value};
use std::{os::fd::{FromRawFd, OwnedFd}, panic::{AssertUnwindSafe, catch_unwind}, sync::{Mutex, atomic::{AtomicBool, Ordering}}, time::Duration, thread::{self, JoinHandle}};
#[cfg(target_os = "android")]
use clash_lib::android::{TunFdGuard, register_tun_fd};
use tokio_util::sync::CancellationToken;
mod config_editor;
mod resource_validator;
mod logging;

struct Instance {
    handle: JoinHandle<()>,
    token: CancellationToken,
    #[cfg(target_os = "android")]
    tun_fd: Option<TunFdGuard>,
}

static INSTANCE: Mutex<Option<Instance>> = Mutex::new(None);
static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);
static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

fn prepare(raw: &str, fd: Option<i32>, secret: &str) -> Result<String, String> {
    let mut config: Mapping = serde_yaml::from_str(raw).map_err(|e| e.to_string())?;
    config_editor::ensure_dns2(&mut config);
    let ipv6 = config.get(Value::String("ipv6".into())).and_then(Value::as_bool).unwrap_or(false);
    let overrides = serde_json::json!({
        "external-controller": "127.0.0.1:19090", "secret": secret,
        "allow-lan": false, "bind-address": "127.0.0.1", "ipv6": ipv6,
        "external-controller-ipc": null, "routing-mark": null,
        "tun": {"enable": fd.is_some(), "device-id": format!("fd://{}", fd.unwrap_or(0)), "stack": "smoltcp",
            "gateway": "172.19.0.1/30", "gateway-v6": if ipv6 { Some("fdfe:dcba:9876::1/126") } else { None },
            "mtu": 1500, "auto-route": false,
            "auto-detect-interface": false, "dns-hijack": ["any:53"]}
    });
    let overlay: Mapping = serde_yaml::from_value(serde_yaml::to_value(overrides).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    config.extend(overlay);
    // The host UID is excluded from the VPN; subscription requests need a
    // loopback inbound to explicitly enter the running proxy engine.
    let has_proxy_port = ["mixed-port", "port", "socks-port"].iter().any(|key| {
        config.get(Value::String((*key).into())).is_some_and(|value| {
            !value.is_null() && value.as_u64() != Some(0) && value.as_str() != Some("0")
        })
    });
    if !has_proxy_port {
        let reserved: Vec<u64> = config.get(Value::String("listeners".into()))
            .and_then(Value::as_sequence).into_iter().flatten()
            .filter_map(|listener| listener.get("port").and_then(Value::as_u64)).collect();
        let port = (19091..19200).find(|port| !reserved.contains(port))
            .ok_or("没有可用的应用本地代理端口")?;
        config.insert(Value::String("mixed-port".into()), Value::Number(port.into()));
    }
    let dns_key = Value::String("dns".into());
    if !config.contains_key(&dns_key) {
        let dns = serde_yaml::from_str::<Value>("enhanced-mode: fake-ip\nnameserver: [https://1.1.1.1/dns-query]")
            .map_err(|e| e.to_string())?;
        config.insert(dns_key.clone(), dns);
    }
    let dns = config.get_mut(&dns_key).and_then(Value::as_mapping_mut)
        .ok_or("dns 必须是 YAML 映射")?;
    dns.insert(Value::String("enable".into()), Value::Bool(false));
    dns.insert(Value::String("ipv6".into()), Value::Bool(ipv6));
    dns.insert(Value::String("listen".into()), Value::String("127.0.0.1:0".into()));
    if let Some(dns2) = config.get_mut(Value::String("dns2".into())).and_then(Value::as_mapping_mut) {
        if dns2.get(Value::String("enable".into())).and_then(Value::as_bool) == Some(true) {
            dns2.insert(Value::String("listen".into()), Value::String("127.0.0.1:0".into()));
            let dns_ipv6 = ipv6 && dns2.get(Value::String("ipv6".into())).and_then(Value::as_bool).unwrap_or(true);
            dns2.insert(Value::String("ipv6".into()), Value::Bool(dns_ipv6));
        }
    }
    serde_yaml::to_string(&config).map_err(|e| e.to_string())
}

fn network_config(raw: &str, mode: &str) -> Result<String, String> {
    let mut config: Mapping = serde_yaml::from_str(raw).map_err(|e| e.to_string())?;
    config_editor::ensure_dns2(&mut config);
    let ipv6 = match mode {
        "on" => true,
        "off" => false,
        "profile" => match config.get(Value::String("ipv6".into())) {
            Some(Value::Bool(value)) => *value,
            None => false,
            _ => return Err("ipv6 必须为 true 或 false".into()),
        },
        _ => return Err("无效的 IPv6 设置".into()),
    };
    config.insert(Value::String("ipv6".into()), Value::Bool(ipv6));
    serde_json::to_string(&serde_json::json!({
        "ipv6": ipv6,
        "dns2": config.get(Value::String("dns2".into())),
        "config": serde_yaml::to_string(&config).map_err(|e| e.to_string())?,
    })).map_err(|e| e.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_networkConfig(
    mut env: JNIEnv, _this: JObject, config: JString, mode: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let raw: String = env.get_string(&config).map_err(|e| e.to_string())?.into();
        let mode: String = env.get_string(&mode).map_err(|e| e.to_string())?.into();
        network_config(&raw, &mode)
    })();
    match result {
        Ok(value) => env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); std::ptr::null_mut() }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_start(
    mut env: JNIEnv, _this: JObject, config: JString, directory: JString, secret: JString,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let mut instance = INSTANCE.lock().map_err(|e| e.to_string())?;
        if instance.is_some() { return Err("内核已启动".into()); }
        STOP_REQUESTED.store(false, Ordering::SeqCst);
        let raw: String = env.get_string(&config).map_err(|e| e.to_string())?.into();
        let cwd: String = env.get_string(&directory).map_err(|e| e.to_string())?.into();
        let token: String = env.get_string(&secret).map_err(|e| e.to_string())?.into();
        let yaml = prepare(&raw, None, &token)?;
        let parsed = Config::Str(yaml).try_parse().map_err(|e| e.to_string())?;
        let log_tx = logging::channel(parsed.general.log_level, &cwd)?;
        let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().build()
            .map_err(|e| e.to_string())?;
        LAST_ERROR.lock().unwrap().clear();
        let token = CancellationToken::new();
        let cancellation = token.clone();
        let handle = thread::Builder::new().name("clash-core".into()).spawn(move || {
            let result = catch_unwind(AssertUnwindSafe(|| runtime.block_on(async {
                let core = start(parsed, cwd, None, log_tx, cancellation.clone());
                tokio::pin!(core);
                tokio::select! {
                    result = &mut core => result,
                    _ = cancellation.cancelled() => match tokio::time::timeout(Duration::from_secs(5), &mut core).await {
                        Ok(result) => result,
                        Err(_) => Err(clash_lib::Error::Operation("内核停止超时，已取消运行任务".into())),
                    },
                }
            })));
            let message = match result {
                Ok(Ok(())) => String::new(),
                Ok(Err(error)) => error.to_string(),
                Err(payload) => payload.downcast_ref::<String>().cloned()
                    .or_else(|| payload.downcast_ref::<&str>().map(|s| s.to_string()))
                    .unwrap_or_else(|| "内核线程异常退出".into()),
            };
            *LAST_ERROR.lock().unwrap() = message;
            runtime.shutdown_timeout(Duration::from_secs(1));
        }).map_err(|e| e.to_string())?;
        if STOP_REQUESTED.load(Ordering::SeqCst) { token.cancel(); }
        *instance = Some(Instance {
            handle, token,
            #[cfg(target_os = "android")]
            tun_fd: None,
        });
        Ok(())
    })();
    env.new_string(result.err().unwrap_or_default()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_attachTun(
    mut env: JNIEnv, _this: JObject, config: JString, fd: jint, secret: JString,
) -> jstring {
    // JNI transfers a unique fd. The registration guard closes it on every
    // pre-adoption failure, while tun-rs becomes the sole owner after adoption.
    let descriptor = unsafe { OwnedFd::from_raw_fd(fd) };
    let result = (|| -> Result<String, String> {
        let mut state = INSTANCE.lock().map_err(|e| e.to_string())?;
        let instance = state.as_mut().filter(|instance| !instance.handle.is_finished())
            .ok_or("内核尚未就绪")?;
        let raw: String = env.get_string(&config).map_err(|e| e.to_string())?.into();
        let token: String = env.get_string(&secret).map_err(|e| e.to_string())?.into();
        let yaml = prepare(&raw, Some(fd), &token)?;
        Config::Str(yaml.clone()).try_parse().map_err(|e| e.to_string())?;
        #[cfg(target_os = "android")]
        {
            if instance.tun_fd.is_some() { return Err("VPN 接口已经接入".into()); }
            instance.tun_fd = Some(register_tun_fd(descriptor));
        }
        #[cfg(not(target_os = "android"))]
        let _ = (instance, descriptor);
        Ok(yaml)
    })();
    match result {
        Ok(yaml) => env.new_string(yaml).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error); std::ptr::null_mut() }
    }
}

// Signal cancellation immediately without waiting for the Android worker or a join.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_requestStop(_env: JNIEnv, _this: JObject) {
    STOP_REQUESTED.store(true, Ordering::SeqCst);
    if let Ok(state) = INSTANCE.try_lock() {
        if let Some(instance) = state.as_ref() { instance.token.cancel(); }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_stop(_env: JNIEnv, _this: JObject) {
    if let Ok(mut instance) = INSTANCE.lock() {
        if let Some(instance) = instance.take() {
            instance.token.cancel();
            #[cfg(target_os = "android")]
            if let Some(tun_fd) = &instance.tun_fd { tun_fd.close_pending(); }
            let _ = instance.handle.join();
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_alive(_env: JNIEnv, _this: JObject) -> jboolean {
    INSTANCE.lock().ok().and_then(|i| i.as_ref().map(|instance| !instance.handle.is_finished())).unwrap_or(false) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_lastError(env: JNIEnv, _this: JObject) -> jstring {
    env.new_string(LAST_ERROR.lock().unwrap().as_str())
        .map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn configured_group_order(raw: &str) -> Result<Vec<String>, String> {
    let config: Mapping = serde_yaml::from_str(raw).map_err(|e| e.to_string())?;
    let groups = config.get(Value::String("proxy-groups".into())).and_then(Value::as_sequence);
    Ok(groups.into_iter().flatten().filter_map(|group| {
        group.get("name").and_then(Value::as_str).map(str::to_owned)
    }).collect())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_groupOrder(
    mut env: JNIEnv, _this: JObject, config: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let raw: String = env.get_string(&config).map_err(|e| e.to_string())?.into();
        serde_json::to_string(&configured_group_order(&raw)?).map_err(|e| e.to_string())
    })();
    match result {
        Ok(value) => env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); std::ptr::null_mut() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn runtime_overrides_prevent_lan_exposure_and_preserve_routes() {
        let output = prepare("allow-lan: true\nsecret: untrusted\nrules: [MATCH,DIRECT]\n", Some(42), "private").unwrap();
        let parsed: Value = serde_yaml::from_str(&output).unwrap();
        assert_eq!(parsed["allow-lan"], false);
        assert_eq!(parsed["secret"], "private");
        assert_eq!(parsed["tun"]["device-id"], "fd://42");
        assert!(parsed["rules"].is_sequence());
        assert!(prepare("- invalid-root", Some(42), "private").is_err());
    }
    #[test]
    fn prepared_config_is_accepted_by_the_real_core() {
        let output = prepare("mode: rule\nproxies: []\nrules: ['MATCH,DIRECT']\n", Some(42), "private").unwrap();
        assert!(Config::Str(output).try_parse().is_ok());
    }

    #[test]
    fn vpn_dns_is_enabled_without_discarding_user_resolvers() {
        let output = prepare("dns:\n  enable: false\n  nameserver: [1.1.1.1]\n", Some(42), "private").unwrap();
        let parsed: Value = serde_yaml::from_str(&output).unwrap();
        assert_eq!(parsed["dns"]["enable"], false);
        assert_eq!(parsed["dns2"]["enable"], true);
        assert_eq!(parsed["dns"]["nameserver"][0], "1.1.1.1");
        assert_eq!(parsed["dns"]["listen"], "127.0.0.1:0");
    }

    #[test]
    fn preparation_keeps_vpn_disabled_and_drops_desktop_routing_mark() {
        let output = prepare("routing-mark: 7894\nrules: ['MATCH,DIRECT']\n", None, "private").unwrap();
        let parsed: Value = serde_yaml::from_str(&output).unwrap();
        assert_eq!(parsed["tun"]["enable"], false);
        assert!(parsed["routing-mark"].is_null());
        assert!(Config::Str(output).try_parse().is_ok());
    }

    #[test]
    fn dns2_keeps_user_upstreams_with_a_local_runtime_listener() {
        let output = prepare("dns2:\n  enable: true\n  listen: '[::]:1053'\n  upstreams: [{tag: direct, type: remote, servers: [223.5.5.5]}]\n", None, "private").unwrap();
        let parsed: Value = serde_yaml::from_str(&output).unwrap();
        assert_eq!(parsed["dns2"]["listen"], "127.0.0.1:0");
        assert_eq!(parsed["dns2"]["ipv6"], false);
        assert_eq!(parsed["dns2"]["upstreams"][0]["servers"][0], "223.5.5.5");
    }

    #[test]
    fn proxy_group_order_preserves_yaml_sequence_and_decodes_names() {
        let raw = r#"proxy-groups:
  - name: '🚀 节点选择'
    type: select
  - name: '♻️ 自动选择'
    type: url-test
  - name: 'AI'
    type: select
"#;
        assert_eq!(configured_group_order(raw).unwrap(), vec!["🚀 节点选择", "♻️ 自动选择", "AI"]);
        assert!(configured_group_order("proxies: []").unwrap().is_empty());
    }

    #[test]
    fn ipv6_network_setting_follows_profile_or_overrides_it() {
        for (raw, mode, expected) in [
            ("ipv6: true", "profile", true), ("ipv6: false", "profile", false),
            ("proxies: []", "profile", false), ("ipv6: false", "on", true),
            ("ipv6: true", "off", false),
        ] {
            let network: serde_json::Value = serde_json::from_str(&network_config(raw, mode).unwrap()).unwrap();
            assert_eq!(network["ipv6"], expected);
            let parsed: Value = serde_yaml::from_str(network["config"].as_str().unwrap()).unwrap();
            assert_eq!(parsed["ipv6"], expected);
        }
        assert!(network_config("ipv6: nonsense", "profile").is_err());
        assert!(network_config("{}", "invalid").is_err());
    }

    #[test]
    fn ipv6_tun_gateway_and_both_dns_resolvers_match_network_setting() {
        for enabled in [true, false] {
            let raw = format!("ipv6: {enabled}\ndns2:\n  enable: true\n  upstreams: [{{tag: direct, type: remote, servers: [223.5.5.5]}}]\nrules: ['MATCH,DIRECT']\n");
            let output = prepare(&raw, Some(42), "private").unwrap();
            let parsed: Value = serde_yaml::from_str(&output).unwrap();
            assert_eq!(parsed["ipv6"], enabled);
            assert_eq!(parsed["dns"]["ipv6"], enabled);
            assert_eq!(parsed["dns2"]["ipv6"], enabled);
            if enabled {
                assert_eq!(parsed["tun"]["gateway-v6"], "fdfe:dcba:9876::1/126");
            } else {
                assert!(parsed["tun"]["gateway-v6"].is_null());
            }
            assert!(Config::Str(output).try_parse().is_ok());
        }
    }

}
