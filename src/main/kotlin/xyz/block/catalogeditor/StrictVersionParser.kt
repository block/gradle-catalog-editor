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

public class StrictVersionParser {
  public fun parse(version: String?): RichVersion {
    if (version == null) {
      return RichVersion.EMPTY
    }
    val idx = version.indexOf("!!")
    if (idx == 0) {
      throw CatalogParsingException("The strict version modifier (!!) must be appended to a valid version number")
    }
    if (idx > 0) {
      val strictly = version.substring(0, idx)
      val prefer = version.substring(idx + 2)
      return RichVersion(null, strictly, prefer)
    }
    return RichVersion(version, null, null)
  }

  public class RichVersion(
    @JvmField public val require: String?,
    @JvmField public val strictly: String?,
    @JvmField public val prefer: String?,
  ) {
    public companion object {
      public val EMPTY: RichVersion = RichVersion(null, null, null)
    }
  }
}
