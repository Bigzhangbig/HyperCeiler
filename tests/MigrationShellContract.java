import com.sevtinge.hyperceiler.expansion.utils.ShellSafeUtils;
import com.sevtinge.hyperceiler.hook.utils.prefs.LegacyPrefsMigration;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks actual published command validation, without running su or reading any real device files. */
public final class MigrationShellContract {
    public static void main(String[] args) throws Exception {
        AtomicInteger checked = new AtomicInteger();
        LegacyPrefsMigration.prepare(command -> {
            int stage = checked.incrementAndGet();
            if (!ShellSafeUtils.isSafeCommand(command)) {
                throw new AssertionError("Published shell validator rejects migration stage " + stage);
            }
            if (stage == 1) return "1\t/data/misc/hyperceiler-check/prefs/com.sevtinge.hyperceiler/hyperceiler_prefs.xml\n"
                + LegacyPrefsMigration.SCAN_OK;
            // Stop before file/XML handling: this check is solely the real command validator boundary.
            throw new IOException("Command validation probe complete");
        }, Files.createTempDirectory("hyperceiler-command-check").toFile());
        if (checked.get() != 2) throw new AssertionError("Expected both generated scan and read commands");
        System.out.println("Both production migration commands pass the published shell validator");
    }
}
