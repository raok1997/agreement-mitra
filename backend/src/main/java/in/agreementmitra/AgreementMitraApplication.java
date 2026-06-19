package in.agreementmitra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "AgreementMitra")
@SpringBootApplication
public class AgreementMitraApplication {
  public static void main(String[] args) {
    SpringApplication.run(AgreementMitraApplication.class, args);
  }
}
