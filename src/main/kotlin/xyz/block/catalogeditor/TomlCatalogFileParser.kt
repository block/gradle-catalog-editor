/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.block.catalogeditor

import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlInvalidTypeException
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable

internal class TomlCatalogFileParser(
  private val catalogFilePath: Path,
  private val versionCatalogBuilder: VersionCatalogBuilder,
) {
  @Throws(IOException::class)
  private fun parse() {
    val strictVersionParser = StrictVersionParser()
    BufferedInputStream(Files.newInputStream(catalogFilePath)).use { inputStream ->
      val result = Toml.parse(inputStream)
      assertNoParseErrors(result, catalogFilePath)
      val metadataTable = result.getTable(METADATA_KEY)
      verifyMetadata(metadataTable)
      val librariesTable = result.getTable(LIBRARIES_KEY)
      val bundlesTable = result.getTable(BUNDLES_KEY)
      val versionsTable = result.getTable(VERSIONS_KEY)
      val pluginsTable = result.getTable(PLUGINS_KEY)
      val unknownTle = result.keySet().minus(TOP_LEVEL_ELEMENTS)
      if (unknownTle.isNotEmpty()) {
        throw throwVersionCatalogProblemException("unknown top level elements $unknownTle in version catalog")
      }
      parseLibraries(librariesTable, strictVersionParser)
      parsePlugins(pluginsTable, strictVersionParser)
      parseBundles(bundlesTable)
      parseVersions(versionsTable, strictVersionParser)
    }
  }

  private fun assertNoParseErrors(result: TomlParseResult, catalogFilePath: Path) {
    if (result.hasErrors()) {
      val errors = result.errors()
      throw throwVersionCatalogProblemException(
        "parsing failed in " +
          catalogFilePath.toAbsolutePath().toString() +
          " at line " +
          errors[0].position().line() +
          " column " +
          errors[0].position().column()
      )
    }
  }

  private fun verifyMetadata(metadataTable: TomlTable?) {
    if (metadataTable != null) {
      val format = metadataTable.getString("format.version")
      if (format != null && CURRENT_VERSION != format) {
        throw throwVersionCatalogProblemException("unsupported version catalog format $format")
      }
    }
  }

  private fun parseLibraries(librariesTable: TomlTable?, strictVersionParser: StrictVersionParser) {
    if (librariesTable == null) {
      return
    }
    librariesTable.keySet().stream().sorted(Comparator.comparing { obj: String -> obj.length }).forEach { alias: String
      ->
      parseLibrary(alias, librariesTable, versionCatalogBuilder, strictVersionParser)
    }
  }

  private fun parsePlugins(pluginsTable: TomlTable?, strictVersionParser: StrictVersionParser) {
    if (pluginsTable == null) {
      return
    }
    pluginsTable.keySet().stream().sorted(Comparator.comparing { obj: String -> obj.length }).forEach { alias: String ->
      parsePlugin(alias, pluginsTable, versionCatalogBuilder, strictVersionParser)
    }
  }

  private fun parseVersions(versionsTable: TomlTable?, strictVersionParser: StrictVersionParser) {
    if (versionsTable == null) {
      return
    }
    versionsTable.keySet().stream().sorted(Comparator.comparing { obj: String -> obj.length }).forEach { alias: String
      ->
      parseVersion(alias, versionsTable, versionCatalogBuilder, strictVersionParser)
    }
  }

  private fun parseBundles(bundlesTable: TomlTable?) {
    if (bundlesTable == null) {
      return
    }
    bundlesTable.keySet().stream().sorted().forEach { alias: String ->
      val bundled =
        expectArray("bundle", alias, bundlesTable, alias)!!
          .toList()
          .stream()
          .map { obj: Any? -> java.lang.String.valueOf(obj) }
          .collect(Collectors.toList())
      versionCatalogBuilder.bundle(alias, bundled)
    }
  }

  private fun expectString(kind: String, name: String, table: TomlTable, element: String?): String? {
    try {
      var path = name
      if (element != null) {
        path += ".$element"
      }
      return notEmpty(table.getString(path), element, name)
    } catch (ex: TomlInvalidTypeException) {
      throw throwUnexpectedTypeError(kind, name, "a string", ex)
    }
  }

  private fun throwUnexpectedTypeError(
    kind: String,
    name: String,
    typeLabel: String,
    ex: TomlInvalidTypeException,
  ): RuntimeException {
    throw throwVersionCatalogProblemException("unexpected type for $kind '$name'; expected $typeLabel", ex)
  }

  private fun expectArray(kind: String, alias: String, table: TomlTable, element: String): TomlArray? {
    try {
      return table.getArray(element)
    } catch (ex: TomlInvalidTypeException) {
      throw throwUnexpectedTypeError(kind, alias, "an array", ex)
    }
  }

  private fun expectBoolean(kind: String, alias: String, table: TomlTable, element: String): Boolean? {
    try {
      return table.getBoolean(element)
    } catch (ex: TomlInvalidTypeException) {
      throw throwUnexpectedTypeError(kind, alias, "a boolean", ex)
    }
  }

  private fun parseLibrary(
    alias: String,
    librariesTable: TomlTable,
    versionCatalogBuilder: VersionCatalogBuilder,
    strictVersionParser: StrictVersionParser,
  ) {
    val gav = librariesTable[alias]
    if (gav is String) {
      val split = gav.split(":").map { it.trim() }
      if (split.size != 3) {
        throw throwVersionCatalogProblemException(
          "on alias '$alias' notation '$gav' is not a valid dependency notation."
        )
      }
      val group = notEmpty(split[0], "group", alias)!!
      val name = notEmpty(split[1], "name", alias)!!
      val version = notEmpty(split[2], "version", alias)
      val rich = strictVersionParser.parse(version)
      registerDependency(
        versionCatalogBuilder,
        alias,
        group,
        name,
        null,
        rich.require,
        rich.strictly,
        rich.prefer,
        null,
        null,
      )
      return
    }
    if (gav is TomlTable) {
      expectedKeys(gav, LIBRARY_COORDINATES, "library declaration '$alias'")
    }
    var group = expectString("alias", alias, librariesTable, "group")
    var name = expectString("alias", alias, librariesTable, "name")
    val version = librariesTable["$alias.version"]
    val mi = expectString("alias", alias, librariesTable, "module")
    if (mi != null) {
      val split = mi.split(":").map { it.trim() }
      if (split.size == 2) {
        group = notEmpty(split[0], "group", alias)
        name = notEmpty(split[1], "name", alias)
      } else {
        throw throwVersionCatalogProblemException("on alias '$alias' module '$mi' is not a valid module notation.")
      }
    }
    var versionRef: String? = null
    var require: String? = null
    var strictly: String? = null
    var prefer: String? = null
    var rejectedVersions: List<String>? = null
    var rejectAll: Boolean? = null
    if (version is String) {
      require = version
      val richVersion = strictVersionParser.parse(require)
      require = richVersion.require
      prefer = richVersion.prefer
      strictly = richVersion.strictly
    } else if (version is TomlTable) {
      val versionTable = version
      expectedKeys(versionTable, VERSION_KEYS, "version declaration of alias '$alias'")
      versionRef = notEmpty(versionTable.getString("ref"), "version reference", alias)
      require = notEmpty(versionTable.getString("require"), "required version", alias)
      prefer = notEmpty(versionTable.getString("prefer"), "preferred version", alias)
      strictly = notEmpty(versionTable.getString("strictly"), "strict version", alias)
      val rejectedArray = expectArray("alias", alias, versionTable, "reject")
      rejectedVersions =
        rejectedArray
          ?.toList()
          ?.map { obj: Any? -> java.lang.String.valueOf(obj) }
          ?.mapNotNull { v: String? -> notEmpty(v, "rejected version", alias) }
      rejectAll = expectBoolean("alias", alias, versionTable, "rejectAll")
    } else if (version != null) {
      throw throwUnexpectedVersionSyntax(alias, version)
    }
    if (group == null) {
      // ProblemIds for "subtypes" of a problem
      throw throwVersionCatalogAliasException(alias, "group")
    }
    if (name == null) {
      throw throwVersionCatalogAliasException(alias, "name")
    }
    registerDependency(
      versionCatalogBuilder,
      alias,
      group,
      name,
      versionRef,
      require,
      strictly,
      prefer,
      rejectedVersions,
      rejectAll,
    )
  }

  private fun throwVersionCatalogAliasException(alias: String, aliasType: String): RuntimeException {
    throw throwVersionCatalogProblemException("Alias definition '$alias' is invalid; missing property $aliasType")
  }

  private fun parsePlugin(
    alias: String,
    librariesTable: TomlTable,
    versionCatalogBuilder: VersionCatalogBuilder,
    strictVersionParser: StrictVersionParser,
  ) {
    val coordinates = librariesTable[alias]
    if (coordinates is String) {
      val split = coordinates.split(":").map { it.trim() }
      if (split.size == 2) {
        val id = notEmpty(split[0], "id", alias)!!
        val version = notEmpty(split[1], "version", alias)
        val rich = strictVersionParser.parse(version)
        registerPlugin(versionCatalogBuilder, alias, id, null, rich.require, rich.strictly, rich.prefer, null, null)
        return
      } else {
        throw throwVersionCatalogProblemException(
          "on alias '$alias' notation '$coordinates' is not a valid plugin notation."
        )
      }
    }
    if (coordinates is TomlTable) {
      expectedKeys(coordinates, PLUGIN_COORDINATES, "plugin declaration '$alias'")
    }
    val id = expectString("alias", alias, librariesTable, "id")
    val version = librariesTable["$alias.version"]
    var versionRef: String? = null
    var require: String? = null
    var strictly: String? = null
    var prefer: String? = null
    var rejectedVersions: List<String>? = null
    var rejectAll: Boolean? = null
    if (version is String) {
      require = version
      val richVersion = strictVersionParser.parse(require)
      require = richVersion.require
      prefer = richVersion.prefer
      strictly = richVersion.strictly
    } else if (version is TomlTable) {
      val versionTable = version
      expectedKeys(versionTable, VERSION_KEYS, "version declaration of alias '$alias'")
      versionRef = notEmpty(versionTable.getString("ref"), "version reference", alias)
      require = notEmpty(versionTable.getString("require"), "required version", alias)
      prefer = notEmpty(versionTable.getString("prefer"), "preferred version", alias)
      strictly = notEmpty(versionTable.getString("strictly"), "strict version", alias)
      val rejectedArray = expectArray("alias", alias, versionTable, "reject")
      rejectedVersions =
        rejectedArray
          ?.toList()
          ?.map { obj: Any? -> java.lang.String.valueOf(obj) }
          ?.mapNotNull { v: String? -> notEmpty(v, "rejected version", alias) }
      rejectAll = expectBoolean("alias", alias, versionTable, "rejectAll")
    } else if (version != null) {
      throw throwUnexpectedVersionSyntax(alias, version)
    }
    if (id == null) {
      throw throwVersionCatalogProblemException("Alias definition '$alias' is invalid")
    }
    registerPlugin(versionCatalogBuilder, alias, id, versionRef, require, strictly, prefer, rejectedVersions, rejectAll)
  }

  private fun throwUnexpectedVersionSyntax(alias: String, version: Any): RuntimeException {
    throw throwVersionCatalogProblemException(
      "Alias definition '$alias' is invalid; got unexpected ${version::class.java.simpleName} instead of string or table"
    )
  }

  private fun parseVersion(
    alias: String,
    versionsTable: TomlTable,
    builder: VersionCatalogBuilder,
    strictVersionParser: StrictVersionParser,
  ) {
    var require: String? = null
    var strictly: String? = null
    var prefer: String? = null
    var rejectedVersions: List<String>? = null
    var rejectAll: Boolean? = null
    val version = versionsTable[alias]
    if (version is String) {
      require = notEmpty(version as String?, "version", alias)
      val richVersion = strictVersionParser.parse(require)
      require = richVersion.require
      prefer = richVersion.prefer
      strictly = richVersion.strictly
    } else if (version is TomlTable) {
      val versionTable = version
      require = notEmpty(versionTable.getString("require"), "required version", alias)
      prefer = notEmpty(versionTable.getString("prefer"), "preferred version", alias)
      strictly = notEmpty(versionTable.getString("strictly"), "strict version", alias)
      val rejectedArray = expectArray("alias", alias, versionTable, "reject")
      rejectedVersions =
        rejectedArray
          ?.toList()
          ?.map { obj: Any? -> java.lang.String.valueOf(obj) }
          ?.mapNotNull { v: String? -> notEmpty(v, "rejected version", alias) }
      rejectAll = expectBoolean("alias", alias, versionTable, "rejectAll")
    } else if (version != null) {
      throw throwUnexpectedVersionSyntax(alias, version)
    }
    registerVersion(builder, alias, require, strictly, prefer, rejectedVersions, rejectAll)
  }

  private fun notEmpty(string: String?, member: String?, alias: String): String? {
    if (string == null) {
      return null
    }
    if (string.isEmpty()) {
      throw throwVersionCatalogProblemException("Alias definition '$alias' is invalid; $member must not be empty.")
    }
    return string
  }

  private fun throwVersionCatalogProblemException(msg: String, cause: Throwable? = null): RuntimeException {
    return CatalogParsingException(msg, cause)
  }

  public companion object {
    public const val CURRENT_VERSION: String = "1.1"
    private const val METADATA_KEY = "metadata"
    private const val LIBRARIES_KEY = "libraries"
    private const val BUNDLES_KEY = "bundles"
    private const val VERSIONS_KEY = "versions"
    private const val PLUGINS_KEY = "plugins"
    private val TOP_LEVEL_ELEMENTS: Set<String?> =
      setOf(METADATA_KEY, LIBRARIES_KEY, BUNDLES_KEY, VERSIONS_KEY, PLUGINS_KEY)
    private val PLUGIN_COORDINATES: Set<String?> = setOf("id", "version")
    private val LIBRARY_COORDINATES: Set<String?> = setOf("group", "name", "version", "module")
    private val VERSION_KEYS: Set<String?> = setOf("ref", "require", "strictly", "prefer", "reject", "rejectAll")

    @Throws(IOException::class)
    public fun parse(catalogFilePath: Path, builder: VersionCatalogBuilder) {
      TomlCatalogFileParser(catalogFilePath, builder).parse()
    }

    private fun expectedKeys(table: TomlTable, allowedKeys: Set<String?>, context: String) {
      val actualKeys = table.keySet()
      if (!allowedKeys.containsAll(actualKeys)) {
        val difference: Set<String?> = actualKeys.minus(allowedKeys)
        throw CatalogParsingException(
          "On " +
            context +
            " expected to find any of " +
            allowedKeys.joinToString(" or ") +
            " but found unexpected key(s) " +
            difference.joinToString(" and ") +
            "."
        )
      }
    }

    private fun registerDependency(
      builder: VersionCatalogBuilder,
      alias: String,
      group: String,
      name: String,
      versionRef: String?,
      require: String?,
      strictly: String?,
      prefer: String?,
      rejectedVersions: List<String>?,
      rejectAll: Boolean?,
    ) {
      val aliasBuilder = builder.library(alias, group, name)
      if (versionRef != null) {
        aliasBuilder.versionRef(versionRef)
        return
      }
      aliasBuilder.version { v: VersionCatalogBuilder.RichVersionBuilder ->
        configureVersion(require, strictly, prefer, rejectedVersions, rejectAll, v)
      }
    }

    private fun configureVersion(
      require: String?,
      strictly: String?,
      prefer: String?,
      rejectedVersions: List<String>?,
      rejectAll: Boolean?,
      v: VersionCatalogBuilder.RichVersionBuilder,
    ) {
      if (require != null) {
        v.require(require)
      }
      if (strictly != null) {
        v.strictly(strictly)
      }
      if (prefer != null) {
        v.prefer(prefer)
      }
      if (rejectedVersions != null) {
        v.reject(*rejectedVersions.toTypedArray<String>())
      }
      if (rejectAll != null && rejectAll) {
        v.rejectAll()
      }
    }

    private fun registerPlugin(
      builder: VersionCatalogBuilder,
      alias: String,
      id: String,
      versionRef: String?,
      require: String?,
      strictly: String?,
      prefer: String?,
      rejectedVersions: List<String>?,
      rejectAll: Boolean?,
    ) {
      val aliasBuilder = builder.plugin(alias, id)
      if (versionRef != null) {
        aliasBuilder.versionRef(versionRef)
        return
      }
      aliasBuilder.version { v: VersionCatalogBuilder.RichVersionBuilder ->
        configureVersion(require, strictly, prefer, rejectedVersions, rejectAll, v)
      }
    }

    private fun registerVersion(
      builder: VersionCatalogBuilder,
      alias: String,
      require: String?,
      strictly: String?,
      prefer: String?,
      rejectedVersions: List<String>?,
      rejectAll: Boolean?,
    ) {
      builder.version(alias) { v: VersionCatalogBuilder.RichVersionBuilder ->
        configureVersion(require, strictly, prefer, rejectedVersions, rejectAll, v)
      }
    }
  }
}
