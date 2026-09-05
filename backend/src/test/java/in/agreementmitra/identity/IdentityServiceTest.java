package in.agreementmitra.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Find-or-create by external credential (task 5.4), with mocked repositories and no Spring context.
 * The first login for a subject inserts one identity + one credential; a repeat reuses them; a
 * different subject yields a distinct identity.
 */
@ExtendWith(MockitoExtension.class)
class IdentityServiceTest {

  private static final String GOOGLE = "GOOGLE";

  @Mock private IdentityRepository identities;
  @Mock private IdentityCredentialRepository credentials;
  @InjectMocks private IdentityService service;

  @Test
  void firstLoginCreatesOneIdentityAndOneCredential() {
    when(credentials.findByProviderAndProviderSubject(GOOGLE, "sub-1"))
        .thenReturn(Optional.empty());
    when(identities.save(any(Identity.class))).thenAnswer(inv -> inv.getArgument(0));

    UUID id = service.findOrCreate(GOOGLE, "sub-1", "a@x.com", true, "Asha");

    assertThat(id).isNotNull();
    verify(identities).save(any(Identity.class));
    verify(credentials).save(any(IdentityCredential.class));
  }

  @Test
  void repeatLoginReusesTheIdentityAndCreatesNoNewCredential() {
    UUID existingId = UUID.randomUUID();
    IdentityCredential existing =
        IdentityCredential.create(existingId, GOOGLE, "sub-2", "a@x.com", true);
    when(credentials.findByProviderAndProviderSubject(GOOGLE, "sub-2"))
        .thenReturn(Optional.of(existing));

    UUID id = service.findOrCreate(GOOGLE, "sub-2", "a@x.com", true, "Asha");

    assertThat(id).isEqualTo(existingId);
    verify(identities, never()).save(any());
    verify(credentials, never()).save(any());
  }

  @Test
  void distinctSubjectsBecomeDistinctIdentities() {
    when(credentials.findByProviderAndProviderSubject(any(), any())).thenReturn(Optional.empty());
    when(identities.save(any(Identity.class))).thenAnswer(inv -> inv.getArgument(0));

    UUID a = service.findOrCreate(GOOGLE, "sub-a", "a@x.com", true, "A");
    UUID b = service.findOrCreate(GOOGLE, "sub-b", "b@x.com", true, "B");

    assertThat(a).isNotEqualTo(b);
  }
}
