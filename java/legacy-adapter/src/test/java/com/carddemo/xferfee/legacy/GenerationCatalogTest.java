package com.carddemo.xferfee.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.xferfee.legacy.io.GenerationDataGroup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationCatalogTest {

    private static final String BASE = "AWS.M2.CARDDEMO.ACCTDATA.XFER";

    @Test
    void createsAndAdvancesTheRunjclCatalog(@TempDir Path dir) throws IOException {
        GenerationDataGroup gdg = new GenerationDataGroup(dir, BASE);
        assertThat(gdg.writeNext(out -> out.write('A'))).hasFileName(BASE + ".G0001V00");
        assertThat(Files.readString(dir.resolve(BASE + ".gdg"))).isEqualTo("{\"current\": 1}\n");
        gdg.writeNext(out -> out.write('B'));
        assertThat(Files.readString(dir.resolve(BASE + ".gdg"))).isEqualTo("{\"current\": 2}\n");
        assertThat(gdg.current()).contains(dir.resolve(BASE + ".G0002V00"));
    }

    @Test
    void catalogIsAuthoritativeOverFilesOnDisk(@TempDir Path dir) throws IOException {
        // runjcl leaves rolled-back (+1) generations uncataloged; (0) is what the catalog says
        Files.write(dir.resolve(BASE + ".G0001V00"), new byte[] {'1'});
        Files.write(dir.resolve(BASE + ".G0002V00"), new byte[] {'2'});
        Files.writeString(dir.resolve(BASE + ".gdg"), "{\"current\": 1}\n");
        GenerationDataGroup gdg = new GenerationDataGroup(dir, BASE);
        assertThat(gdg.current()).contains(dir.resolve(BASE + ".G0001V00"));
        assertThatThrownBy(() -> gdg.writeNext(out -> out.write('X')))
                .hasMessageContaining("G0002V00 already exists");
        assertThat(Files.readAllBytes(dir.resolve(BASE + ".G0002V00"))).containsExactly('2');
        assertThat(Files.readString(dir.resolve(BASE + ".gdg"))).isEqualTo("{\"current\": 1}\n");
    }

    @Test
    void failedWriteLeavesCatalogAndDirectoryUntouched(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve(BASE + ".G0001V00"), new byte[] {'1'});
        Files.writeString(dir.resolve(BASE + ".gdg"), "{\"current\": 1}\n");
        GenerationDataGroup gdg = new GenerationDataGroup(dir, BASE);
        assertThatThrownBy(() -> gdg.writeNext(out -> {
            out.write('X');
            throw new IOException("boom");
        })).hasRootCauseMessage("boom");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder(BASE + ".G0001V00", BASE + ".gdg");
        }
        assertThat(Files.readString(dir.resolve(BASE + ".gdg"))).isEqualTo("{\"current\": 1}\n");
    }
}
