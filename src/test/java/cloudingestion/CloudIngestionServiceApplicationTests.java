package cloudingestion;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@AutoConfigureEmbeddedDatabase(provider = EMBEDDED)
class CloudIngestionServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
