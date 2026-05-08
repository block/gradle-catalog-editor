package xyz.block.catalogeditor

public class CatalogParsingException(msg: String, cause: Throwable? = null) : RuntimeException(msg) {
  init {
    cause?.let { initCause(it) }
  }
}
