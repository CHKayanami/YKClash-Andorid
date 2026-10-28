package com.yk.clash

object NativeCore {
    init { System.loadLibrary("ykcore") }
    external fun start(config: String, directory: String, secret: String): String
    external fun attachTun(config: String, fd: Int, secret: String): String
    external fun requestStop()
    external fun stop()
    external fun alive(): Boolean
    external fun lastError(): String
    external fun groupOrder(config: String): String
    external fun networkConfig(config: String, ipv6Mode: String): String
    external fun configDocument(config: String): String
    external fun encodeConfig(document: String): String
    external fun convertYamlMapping(value: String, toYaml: Boolean): String
    external fun validateGeodata(path: String, kind: String): String
    external fun previewConfig(config: String, ipv6Mode: String): String
}
