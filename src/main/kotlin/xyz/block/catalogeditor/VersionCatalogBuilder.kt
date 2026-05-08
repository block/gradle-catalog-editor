package xyz.block.catalogeditor

internal class VersionCatalogBuilder {
  data class RichVersionBuilder(
    private var require: String? = null,
    private var strictly: String? = null,
    private var prefer: String? = null,
    private var reject: List<String>? = null,
    private var rejectAll: Boolean? = null,
    private var branch: String? = null,
  ) {
    public fun strictly(version: String) {
      this.strictly = version
      this.require = null
      clearRejected()
    }

    public fun require(version: String) {
      this.require = version
      this.strictly = null
      clearRejected()
    }

    public fun prefer(version: String) {
      this.prefer = version
      clearRejected()
    }

    public fun reject(vararg versions: String?) {
      reject = versions.filterNotNull()
    }

    public fun rejectAll() {
      rejectAll = true
    }

    private fun clearRejected() {
      reject = null
      rejectAll = null
    }

    fun build(): VersionCatalog.RichVersion? {
      val richVersion =
        VersionCatalog.RichVersion(
          require = require,
          strictly = strictly,
          prefer = prefer,
          reject = reject,
          rejectAll = rejectAll,
          branch = branch,
        )
      // If the version is unspecified the TomlCatalogFileParser configures this object with
      // a bunch of nulls. So we detect that and just null out the whole object instead, as it
      // makes it serialization less lossy.
      return if (richVersion.isNull()) {
        null
      } else {
        richVersion
      }
    }
  }

  internal fun interface Action<T> {
    fun execute(t: T)
  }

  data class LibraryBuilder(
    val group: String,
    val artifact: String,
    var versionRef: String? = null,
    var version: RichVersionBuilder? = null,
  ) {
    public fun version(versionSpec: Action<in RichVersionBuilder>) {
      val version = RichVersionBuilder()
      versionSpec.execute(version)
      this.version = version
      this.versionRef = null
    }

    public fun version(version: String) {
      this.version = RichVersionBuilder(require = version)
      this.versionRef = null
    }

    public fun versionRef(versionRef: String) {
      this.versionRef = versionRef
      this.version = null
    }

    fun build() =
      VersionCatalog.Library(
        group = group,
        artifact = artifact,
        versionRef = versionRef,
        version = version?.build(),
      )
  }

  data class PluginBuilder(val id: String, var versionRef: String? = null, var version: RichVersionBuilder? = null) {
    public fun version(versionSpec: Action<in RichVersionBuilder>) {
      val version = RichVersionBuilder()
      versionSpec.execute(version)
      this.version = version
      this.versionRef = null
    }

    public fun version(version: String) {
      this.version = RichVersionBuilder(require = version)
      this.versionRef = null
    }

    public fun versionRef(versionRef: String) {
      this.versionRef = versionRef
      this.version = null
    }

    fun build() = VersionCatalog.Plugin(id = id, versionRef = versionRef, version = version?.build())
  }

  val versions = mutableMapOf<String, RichVersionBuilder>()
  val libraries = mutableMapOf<String, LibraryBuilder>()
  val plugins = mutableMapOf<String, PluginBuilder>()
  val bundles = mutableMapOf<String, MutableList<String>>()

  public fun version(alias: String, versionSpec: Action<in RichVersionBuilder>): String {
    val version = RichVersionBuilder()
    versionSpec.execute(version)
    versions[alias] = version
    return alias
  }

  public fun version(alias: String, version: String): String {
    versions[alias] = RichVersionBuilder(require = version)
    return alias
  }

  public fun library(alias: String, group: String, artifact: String): LibraryBuilder {
    val library = LibraryBuilder(group, artifact)
    libraries[alias] = library
    return library
  }

  public fun library(alias: String, groupArtifactVersion: String) {
    val (group, artifact, version) = groupArtifactVersion.split(":")
    val library = LibraryBuilder(group, artifact, version)
    libraries[alias] = library
  }

  public fun plugin(alias: String, id: String): PluginBuilder {
    val plugin = PluginBuilder(id)
    plugins[alias] = plugin
    return plugin
  }

  public fun bundle(alias: String, aliases: MutableList<String>) {
    bundles[alias] = aliases
  }
}
