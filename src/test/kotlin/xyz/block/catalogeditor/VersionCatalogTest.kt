package xyz.block.catalogeditor

import java.nio.file.Path
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

internal class VersionCatalogTest {
  @Test
  fun readDependenciesFile(@TempDir tempDir: Path) {
    val inputFile = tempDir.resolve("libs.versions.toml")

    // This input intentionally has inconsistent whitespace to test that still parses correctly
    inputFile.writeText(
      """
      [versions]
      required= "1.0.0"
      strict = "2.0.0!!"
      strictWithPrefer = "2.0.1!!2.0.0"
      preferred = { require = "1.0.1", prefer = "1.1.0" }
      rejected = { reject = ["3.0.0", "4.0.0"] }
      rejectAllVersions = { rejectAll = true }
      [libraries]
      foo = { module="xyz.block.example:foo", version   = "1.0.0" }

      bar = { group = "xyz.block.example", name = "bar", version.ref = "required" }
      banish = { group = "xyz.block.example", name = "block-protos", version = { rejectAll = true } }
      [plugins]
      baz = { id = "xyz.block.example.baz", version = "100+" }
      [bundles]

      qux = ["foo",
             "bar"]
      """
        .trimIndent()
    )

    val dependencies = VersionCatalog.parse(inputFile)

    assertThat(dependencies.versions)
      .containsExactlyInAnyOrderEntriesOf(
        mapOf(
          "required" to VersionCatalog.RichVersion("1.0.0", null, null, null, null, null),
          "strict" to VersionCatalog.RichVersion(null, "2.0.0", "", null, null, null),
          "strictWithPrefer" to VersionCatalog.RichVersion(null, "2.0.1", "2.0.0", null, null, null),
          "preferred" to VersionCatalog.RichVersion("1.0.1", null, "1.1.0", null, null, null),
          "rejected" to VersionCatalog.RichVersion(null, null, null, listOf("3.0.0", "4.0.0"), null, null),
          "rejectAllVersions" to VersionCatalog.RichVersion(null, null, null, null, true, null),
        )
      )
    assertThat(dependencies.libraries)
      .containsExactlyInAnyOrderEntriesOf(
        mapOf(
          "foo" to
            VersionCatalog.Library(
              "xyz.block.example",
              "foo",
              null,
              VersionCatalog.RichVersion("1.0.0", null, null, null, null, null),
            ),
          "bar" to VersionCatalog.Library("xyz.block.example", "bar", "required", null),
          "banish" to
            VersionCatalog.Library(
              "xyz.block.example",
              "block-protos",
              null,
              VersionCatalog.RichVersion(null, null, null, null, true, null),
            ),
        )
      )
    assertThat(dependencies.plugins)
      .containsExactlyInAnyOrderEntriesOf(
        mapOf(
          "baz" to
            VersionCatalog.Plugin(
              "xyz.block.example.baz",
              null,
              VersionCatalog.RichVersion("100+", null, null, null, null, null),
            )
        )
      )
    assertThat(dependencies.bundles).containsExactlyInAnyOrderEntriesOf(mapOf("qux" to listOf("foo", "bar")))
  }

  @Test
  fun `invalid version catalog - not toml`() {
    val catalog =
      """
      garbage
      """
        .trimIndent()

    assertThrows<CatalogParsingException> { VersionCatalog.parseContents(catalog) }
  }

  @Test
  fun `invalid version catalog - versions should be strings`() {
    val catalog =
      """
      [versions]
      required = identifier
      """
        .trimIndent()

    assertThrows<CatalogParsingException> { VersionCatalog.parseContents(catalog) }
  }

  @Test
  fun `invalid version catalog - bundle values should be quoted`() {
    val catalog =
      """
      [libraries]
      foo = { module="xyz.block.example:foo", version = "1.0.0" }
      [bundles]
      qux = [foo]
      """
        .trimIndent()

    assertThrows<CatalogParsingException> { VersionCatalog.parseContents(catalog) }
  }

  @Test
  fun `invalid version catalog - duplicate version keys not allowed`() {
    val catalog =
      """
      [versions]
      required = "1.0.0"
      required = "2.0.0"
      """
        .trimIndent()

    assertThrows<CatalogParsingException> { VersionCatalog.parseContents(catalog) }
  }

  @Test
  fun `invalid version catalog - duplicate library keys not allowed`() {
    val catalog =
      """
      [libraries]
      foo = { module="xyz.block.example:foo", version = "1.0.0" }
      foo = { module="xyz.block.example:foo", version = "2.0.0" }
      """
        .trimIndent()

    assertThrows<CatalogParsingException> { VersionCatalog.parseContents(catalog) }
  }

  @Test
  fun `invalid version catalog - versions must not be empty strings`() {
    val catalog =
      """
      [versions]
      foo = ""
      """
        .trimIndent()

    assertThrows<CatalogParsingException>() { VersionCatalog.parseContents(catalog) }
  }

  @Test
  fun `invalid catalog version - missing versionref`() {
    val catalog =
      """
      [libraries]
      foo = { module="xyz.block.example:foo", version.ref = "blah" }
      """
        .trimIndent()
    assertThrows<IllegalStateException> { VersionCatalog.parseContents(catalog) }
  }

  @Test
  fun parseFromStringUsingInMemoryFS() {
    val deps =
      VersionCatalog.parseContents(
        """
        [libraries]
        foo = { module="xyz.block.example:foo", version = "1.0.0" }
        """
          .trimIndent()
      )
    assertThat(deps.libraries)
      .containsExactlyInAnyOrderEntriesOf(
        mapOf(
          "foo" to
            VersionCatalog.Library(
              "xyz.block.example",
              "foo",
              null,
              VersionCatalog.RichVersion.makeRequire("1.0.0"),
            )
        )
      )
  }

  @Test
  fun testSerialization() {
    val deps =
      VersionCatalog.parseContents(
        """
        [versions]
        required= "1.0.0"
        strict = "2.0.0!!"
        strictWithPrefer = "2.0.1!!2.0.0"
        preferred = { require = "1.0.1", prefer = "1.1.0" }
        rejected = { reject = ["3.0.0", "4.0.0"] }
        rejectAllVersions = { rejectAll = true }
        [libraries]
        foo = { module="xyz.block.example:foo", version   = "1.0.0" }

        bar = { group = "xyz.block.example", name = "bar", version.ref = "required" }
        banish = { group = "xyz.block.example", name = "block-protos", version = { rejectAll = true } }
        [plugins]
        baz = { id = "xyz.block.example.baz", version = "100+" }
        [bundles]

        qux = ["foo",
               "bar"]
        """
          .trimIndent()
      )

    val serialized = deps.serialize()
    val expected =
      """
      [versions]
      preferred = { require = "1.0.1", prefer = "1.1.0" }
      rejectAllVersions = { rejectAll = true }
      rejected = { reject = ["3.0.0", "4.0.0"] }
      required = "1.0.0"
      strict = "2.0.0!!"
      strictWithPrefer = { strictly = "2.0.1", prefer = "2.0.0" }

      [libraries]
      banish = { module = "xyz.block.example:block-protos", version = { rejectAll = true } }
      bar = { module = "xyz.block.example:bar", version.ref = "required" }
      foo = { module = "xyz.block.example:foo", version = "1.0.0" }

      [plugins]
      baz = { id = "xyz.block.example.baz", version = "100+" }

      [bundles]
      qux = ["foo", "bar"]

      """
        .trimIndent()
    assertThat(serialized).isEqualTo(expected)

    // round-trip it again to ensure it doesn't change once normalized
    assertThat(VersionCatalog.parseContents(serialized).serialize()).isEqualTo(expected)
  }

  @Test
  fun testEmptyCatalogSerialization() {
    val deps = VersionCatalog.parseContents("")
    val serialized = deps.serialize()
    assertThat(serialized)
      .isEqualTo(
        """
        [libraries]

        """
          .trimIndent()
      )
  }

  @Test
  fun testEmptyVersionSerialization() {
    val deps =
      VersionCatalog.parseContents(
        """
        [versions]
        someThing = {}
        """
          .trimIndent()
      )

    val seralized = deps.serialize()

    assertThat(seralized)
      .isEqualTo(
        """
        [versions]
        someThing = {  }

        [libraries]

        """
          .trimIndent()
      )
  }

  @Test
  fun testMissingVersionSerialization() {
    val deps =
      VersionCatalog.parseContents(
        """
        [libraries]
        someThing = { module = "no-version:provided" }
        """
          .trimIndent()
      )

    val seralized = deps.serialize()

    assertThat(seralized)
      .isEqualTo(
        """
        [libraries]
        someThing = { module = "no-version:provided" }

        """
          .trimIndent()
      )
  }

  @Test
  fun testEmptyLibraryVersionSerialization() {
    val deps =
      VersionCatalog.parseContents(
        """
        [libraries]
        someThing = { module = "no-version:provided", version = { } }
        """
          .trimIndent()
      )

    val seralized = deps.serialize()

    assertThat(seralized)
      .isEqualTo(
        """
        [libraries]
        someThing = { module = "no-version:provided" }

        """
          .trimIndent()
      )
  }
}
