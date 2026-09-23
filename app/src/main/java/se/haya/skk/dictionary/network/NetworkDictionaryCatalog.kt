package se.haya.skk.dictionary.network

data class NetworkDictionaryCatalogEntry(
    val key: String,
    val name: String,
    val url: String,
    val displayName: String = name,
    val description: String = "",
    val licenseName: String,
    val licenseUrl: String = NetworkDictionaryCatalog.LICENSE_URL,
)

object NetworkDictionaryCatalog {
    const val LICENSE_URL = "https://github.com/skk-dev/dict/blob/master/committers.md"
    const val POSTCODE_LICENSE_URL = "https://github.com/skk-dev/dict/blob/master/zipcode/README.md"

    val entries: List<NetworkDictionaryCatalogEntry> = listOf(
        official("S", "SKK-JISYO.S", "基本的な一般語を収録"),
        official("L", "SKK-JISYO.L", "S の語を含む大規模な一般辞書"),
        official("jinmei", "SKK-JISYO.jinmei", "姓名・人名の読み"),
        official("geo", "SKK-JISYO.geo", "地名の読み"),
        NetworkDictionaryCatalogEntry("zipcode", "SKK-JISYO.zipcode",
            "https://raw.githubusercontent.com/skk-dev/dict/master/zipcode/SKK-JISYO.zipcode",
            "SKK-JISYO.zipcode", "7桁の郵便番号から住所。配布元の更新: 2022年5月", "Public Domain", POSTCODE_LICENSE_URL),
    )

    private fun official(key: String, name: String, description: String) = NetworkDictionaryCatalogEntry(
        key, "SKK-JISYO.$key", "https://skk-dev.github.io/dict/SKK-JISYO.$key.gz", name, description,
        "GPL-2.0-or-later",
    )

    fun find(key: String): NetworkDictionaryCatalogEntry? = entries.firstOrNull { it.key == key }
}
