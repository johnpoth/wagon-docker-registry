/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.github.johnpoth;

import java.util.Optional;
import com.google.cloud.tools.jib.plugins.common.AuthProperty;
import com.google.cloud.tools.jib.plugins.common.InferredAuthException;
import com.google.cloud.tools.jib.plugins.common.InferredAuthProvider;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.Repository;

class MavenServerCredentials implements InferredAuthProvider {

  static final String CREDENTIAL_SOURCE = "Maven authenticationInfo";

  private final AuthenticationInfo authenticationInfo;
  private final Repository repository;

    /**
   * Create new instance.
   *
   * @param authenticationInfo decrypted Maven settings
   */
  MavenServerCredentials(AuthenticationInfo authenticationInfo, Repository repository) {
    this.authenticationInfo = authenticationInfo;
    this.repository = repository;
  }

  /**
   * Retrieves credentials for {@code registry} from AuthenticationInfo.
   *
   * @param registry the registry
   * @return the auth info for the registry, or {@link Optional#empty} if none could be retrieved
   */
  @Override
  public Optional<AuthProperty> inferAuth(String registry) {
      // TODO: Registry is in the form host:port
     if (!repository.getUrl().contains(registry) || this.authenticationInfo == null) {
         return Optional.empty();
     }

    String username = this.authenticationInfo.getUserName();
    String password = this.authenticationInfo.getPassword();

    return Optional.of(
        new AuthProperty() {

          @Override
          public String getUsername() {
            return username;
          }

          @Override
          public String getPassword() {
            return password;
          }

          @Override
          public String getAuthDescriptor() {
            return CREDENTIAL_SOURCE;
          }

          @Override
          public String getUsernameDescriptor() {
            return CREDENTIAL_SOURCE;
          }

          @Override
          public String getPasswordDescriptor() {
            return CREDENTIAL_SOURCE;
          }
        });
  }
}
