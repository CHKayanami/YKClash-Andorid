use jni::{JNIEnv, objects::{JObject, JString}, sys::jstring};
use prost::Message;

#[derive(Message)]
struct SiteList {
    #[prost(message, repeated, tag = "1")]
    entries: Vec<Site>,
}
#[derive(Message)]
struct Site {
    #[prost(string, tag = "1")]
    code: String,
    #[prost(message, repeated, tag = "2")]
    domains: Vec<Domain>,
}
#[derive(Message)]
struct Domain {
    #[prost(int32, tag = "1")]
    kind: i32,
    #[prost(string, tag = "2")]
    value: String,
}

fn validate(path: &str, kind: &str) -> Result<(), String> {
    if kind == "geosite" {
        let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
        let list = SiteList::decode(bytes.as_slice()).map_err(|e| format!("GeoSite 格式无效：{e}"))?;
        if list.entries.is_empty() || !list.entries.iter().any(|site| !site.domains.is_empty()) || list.entries.iter().any(|site| site.code.is_empty() || site.domains.iter().any(|domain| domain.value.is_empty() || !(0..=3).contains(&domain.kind))) {
            return Err("GeoSite 文件缺少有效的域名数据".into());
        }
    } else if kind == "mmdb" || kind == "asn-mmdb" {
        let reader = maxminddb::Reader::open_readfile(path).map_err(|e| format!("MMDB 格式无效：{e}"))?;
        if kind == "asn-mmdb" && !reader.metadata().database_type.to_ascii_lowercase().contains("asn") {
            return Err("所选文件不是 ASN 数据库".into());
        }
        if kind == "mmdb" && reader.metadata().database_type.to_ascii_lowercase().contains("asn") {
            return Err("GeoIP 位置不能使用仅含 ASN 的数据库".into());
        }
    } else { return Err("未知的地理库类型".into()); }
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_yk_clash_NativeCore_validateGeodata(
    mut env: JNIEnv, _this: JObject, path: JString, kind: JString,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let path: String = env.get_string(&path).map_err(|e| e.to_string())?.into();
        let kind: String = env.get_string(&kind).map_err(|e| e.to_string())?.into();
        validate(&path, &kind)
    })();
    env.new_string(result.err().unwrap_or_default()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn geosite_rejects_empty_and_non_protobuf_downloads() {
        let name = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!("yk-geosite-{}-{name}.dat", std::process::id()));
        let file = path.to_str().unwrap();
        std::fs::write(&path, b"<html>error</html>").unwrap();
        assert!(validate(file, "geosite").is_err());
        std::fs::write(&path, []).unwrap();
        assert!(validate(file, "geosite").is_err());
        let list = SiteList { entries: vec![Site { code: "cn".into(), domains: vec![Domain { kind: 2, value: "example.cn".into() }] }] };
        std::fs::write(&path, list.encode_to_vec()).unwrap();
        assert!(validate(file, "geosite").is_ok());
        assert!(validate(file, "mmdb").is_err());
        std::fs::remove_file(path).unwrap();
    }
}
