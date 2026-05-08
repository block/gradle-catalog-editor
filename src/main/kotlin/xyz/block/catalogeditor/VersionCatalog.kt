package xyz.block.catalogeditor

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import java.nio.file.Path
import kotlin.io.path.writeText

public data class VersionCatalog(
  val versions: Map<String, RichVersion>,
  val libraries: Map<String, Library>,
  val plugins: Map<String, Plugin>,
  val bundles: Map<String, List<String>>,
) {
  public data class RichVersion(
    val require: String?,
    val strictly: String?,
    val prefer: String?,
    val reject: List<String>?,
    val rejectAll: Boolean?,
    val branch: String?,
  ) {
    internal fun isNull(): Boolean {
      return require == null &&
        strictly == null &&
        prefer == null &&
        reject == null &&
        rejectAll == null &&
        branch == null
    }

    public fun serialize(): String {
      // Use extended format if any of these fields are non-null. `prefer` is a weird case, can be
      // empty for version strings like "1.0.0!!" but we don't want to use the extended format in
      // that case.
      // Also use the extended format if all the fields are null, so we serialize to `{}` instead of
      // an empty string (which the parser rejects as invalid).
      if (isNull() || !prefer.isNullOrBlank() || reject != null || rejectAll != null || branch != null) {
        val pieces = mutableListOf<String>()
        if (require != null) pieces.add("require = \"$require\"")
        if (strictly != null) pieces.add("strictly = \"$strictly\"")
        if (prefer != null) pieces.add("prefer = \"$prefer\"")
        if (reject != null) pieces.add("reject = [${reject.joinToString(", ") { "\"$it\"" }}]")
        if (rejectAll != null) pieces.add("rejectAll = $rejectAll")
        if (branch != null) pieces.add("branch = \"$branch\"")
        return pieces.joinToString(", ", prefix = "{ ", postfix = " }")
      }
      // if we get here, the only things that could be non-null are `strictly` and `require`, and
      // exactly one of those should be set.
      check((strictly == null) xor (require == null)) { "invalid version: ${toString()}" }
      if (strictly != null) return "\"$strictly!!\""
      if (require != null) return "\"$require\""
      // we should never get here
      throw IllegalStateException("invalid version: ${toString()}")
    }

    public companion object {
      public fun makeNull(): RichVersion {
        return RichVersion(
          require = null,
          strictly = null,
          prefer = null,
          reject = null,
          rejectAll = null,
          branch = null,
        )
      }

      public fun makeRequire(version: String): RichVersion {
        return RichVersion(
          require = version,
          strictly = null,
          prefer = null,
          reject = null,
          rejectAll = null,
          branch = null,
        )
      }
    }
  }

  public data class Library(
    val group: String,
    val artifact: String,
    val versionRef: String?,
    val version: RichVersion?,
  ) {
    init {
      // version and versionRef cannot both be set
      assert((version == null) || (versionRef == null))
    }

    public fun serialize(): String {
      return buildString {
        append("{ module = \"$group:$artifact\"")
        if (versionRef != null) append(", version.ref = \"$versionRef\"")
        if (version != null) append(", version = ${version.serialize()}")
        append(" }")
      }
    }
  }

  public data class Plugin(val id: String, val versionRef: String?, val version: RichVersion?) {
    init {
      check(version == null || versionRef == null) {
        "version ($version) and versionRef ($versionRef) cannot both be set."
      }
    }

    public fun serialize(): String {
      return buildString {
        append("{ id = \"$id\"")
        if (versionRef != null) append(", version.ref = \"$versionRef\"")
        if (version != null) append(", version = ${version.serialize()}")
        append(" }")
      }
    }
  }

  public fun serialize(): String {
    return buildString {
      if (versions.isNotEmpty()) {
        appendLine("[versions]")
        versions.toSortedMap().forEach { (name, version) -> appendLine("$name = ${version.serialize()}") }
        appendLine("")
      }
      // Always have the libraries section, even if empty. All the other sections
      // can be omitted. This is not a Gradle requirement but makes handling of the
      // file easier.
      appendLine("[libraries]")
      libraries.toSortedMap().forEach { (name, library) -> appendLine("$name = ${library.serialize()}") }
      if (plugins.isNotEmpty()) {
        appendLine("")
        appendLine("[plugins]")
        plugins.toSortedMap().forEach { (name, plugin) -> appendLine("$name = ${plugin.serialize()}") }
      }
      if (bundles.isNotEmpty()) {
        appendLine("")
        appendLine("[bundles]")
        bundles.toSortedMap().forEach { (name, bundle) ->
          appendLine("$name = ${bundle.joinToString(", ", prefix = "[", postfix = "]") { "\"$it\"" }}")
        }
      }
    }
  }

  public companion object {
    public const val VERSION_CATALOG_PATH: String = "gradle/libs.versions.toml"

    /**
     * Parses a version catalog from a file and returns it as a VersionCatalog (or throws an exception on failure).
     */
    public fun parse(path: Path): VersionCatalog {
      val builder = VersionCatalogBuilder()
      TomlCatalogFileParser.parse(path, builder)

      // The TomlCatalogFileParser doesn't check version refs are valid, so
      // let's do it here before finalizing the catalog
      builder.libraries.forEach { (k, v) ->
        check(v.versionRef == null || builder.versions.containsKey(v.versionRef)) {
          "Library alias $k references unknown version ${v.versionRef}"
        }
      }
      builder.plugins.forEach { (k, v) ->
        check(v.versionRef == null || builder.versions.containsKey(v.versionRef)) {
          "Plugin alias $k references unknown version ${v.versionRef}"
        }
      }

      return VersionCatalog(
        versions = builder.versions.mapValues { it.value.build() ?: RichVersion.makeNull() },
        libraries = builder.libraries.mapValues { it.value.build() },
        plugins = builder.plugins.mapValues { it.value.build() },
        bundles = builder.bundles.mapValues { it.value.toList() }.toMap(),
      )
    }

    /**
     * Parses a version catalog from a string and returns i as a VersionCatalog (or throws an exception on failure).
     */
    public fun parseContents(catalog: String): VersionCatalog {
      // The TomlCatalogFileParser doesn't have a way to parse from a string, so we write the
      // string to a temporary in-memory file and then parse that.
      Jimfs.newFileSystem(Configuration.unix()).use { fs ->
        val path = fs.getPath("libs.versions.toml")
        path.writeText(catalog)
        return parse(path)
      }
    }
  }
}
