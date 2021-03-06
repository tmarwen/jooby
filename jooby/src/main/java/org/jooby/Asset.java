/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jooby;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nonnull;

/**
 * Usually a public file/resource like javascript, css, images files, etc...
 * An asset consist of content type, stream and last modified since, between others.
 *
 * @author edgar
 * @since 0.1.0
 * @see Jooby#assets(String)
 */
public interface Asset {

  /**
   * @return The asset name (without path).
   */
  @Nonnull String name();

  /**
   * @return The last modified date if possible or -1 when isn't.
   */
  long lastModified();

  /**
   * @return The content of this asset.
   * @throws IOException If content can't be read it.
   */
  @Nonnull InputStream stream() throws IOException;

  /**
   * @return Asset media type.
   */
  @Nonnull MediaType type();
}
