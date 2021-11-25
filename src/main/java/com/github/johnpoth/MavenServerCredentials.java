package com.github.johnpoth;

import java.util.Optional;
import com.google.cloud.tools.jib.plugins.common.AuthProperty;
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
