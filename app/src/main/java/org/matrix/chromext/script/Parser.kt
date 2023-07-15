package org.matrix.chromext.script

import kotlin.text.Regex
import org.json.JSONObject
import org.matrix.chromext.utils.Download
import org.matrix.chromext.utils.Log

private val blocksReg =
    Regex(
        """(?<metablock>[\S\s]*?// ==UserScript==\r?\n([\S\s]*?)\r?\n// ==/UserScript==\s+)(?<code>[\S\s]*)""")
private val metaReg = Regex("""^//\s+@(?<key>[\w-]+)\s+(?<value>.+)""")

fun parseScript(input: String, storage: String?, updateResource: Boolean = false): Script? {
  val blockMatchGroup = blocksReg.matchEntire(input)?.groups as? MatchNamedGroupCollection
  if (blockMatchGroup == null) {
    return null
  }

  val script =
      object {
        var name = "sample"
        var namespace = "ChromeXt"
        var match = mutableListOf<String>()
        var grant = mutableListOf<String>()
        var exclude = mutableListOf<String>()
        var require = mutableListOf<String>()
        val meta = (blockMatchGroup.get("metablock")?.value as String)
        val code = blockMatchGroup.get("code")?.value as String
        var resource = mutableListOf<String>()
        var storage: JSONObject? = null
      }
  script.meta.split("\n").forEach {
    val metaMatchGroup = metaReg.matchEntire(it)?.groups as? MatchNamedGroupCollection
    if (metaMatchGroup != null) {
      val key = metaMatchGroup.get("key")?.value as String
      val value = metaMatchGroup.get("value")?.value as String
      when (key) {
        "name" -> script.name = value.replace(":", "")
        "namespace" -> script.namespace = value
        "match" -> script.match.add(value)
        "include" -> script.match.add(value)
        "grant" ->
            if (value != "none") {
              script.grant.add(value)
            }
        "exclude" -> script.exclude.add(value)
        "require" -> script.require.add(value)
        "resource" -> script.resource.add(value.trim().replace("\\s+".toRegex(), " "))
      }
    }
  }

  if (script.grant.contains("GM_getResourceURL") && !script.grant.contains("GM_getResourceText")) {
    script.grant.add("GM_getResourceText")
  }

  if (!script.grant.contains("GM_xmlhttpRequest")) {
    if (script.require.size > 0 || script.grant.contains("GM_download")) {
      script.grant.add("GM_xmlhttpRequest")
    }
  }

  if (script.grant.contains("GM.getValue") || script.grant.contains("GM_getValue")) {
    runCatching { script.storage = JSONObject(storage!!) }
        .onFailure { script.storage = JSONObject() }
  }

  if (script.match.size == 0) {
    return null
  } else {
    val parsed =
        Script(
            (script.namespace + ":" + script.name).replace("\\", ""),
            script.match.toTypedArray(),
            script.grant.toTypedArray(),
            script.exclude.toTypedArray(),
            script.resource.toTypedArray(),
            script.meta,
            script.code,
            script.storage)
    val id = parsed.id
    if (parsed.grant.contains("GM_getResourceText")) {
      parsed.resource.forEach {
        val content = it.split(" ")
        val name = content.first()
        val url = content.last().split("#").first()
        Log.d("Downloading resource for ${name}: ${url}")
        if (url.startsWith("http")) {
          Download.start(url, resourcePath(id, name), true, updateResource)
        }
      }
    }
    return parsed
  }
}

const val RESERVED_CHARS = "|\\?*<\":>+[]/' "

fun resourcePath(id: String, name: String): String =
    "Resource/" +
        id.filterNot { RESERVED_CHARS.contains(it) } +
        "/" +
        name.filterNot { RESERVED_CHARS.contains(it) }
