import com.android.repository.api.*;
import com.android.repository.impl.meta.SchemaModuleUtil;
import com.android.sdklib.repository.AndroidSdkHandler;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Collections;

/** Read existing acceptance; never setAccepted, download or modify SDK files. */
public final class ExistingSdkLicense {
    public static void main(String[] args) throws Exception {
        Path host = Path.of(args[0]);
        String[] paths = {"emulator", "system-images;android-35;default;x86_64"};
        String[] versions = {"37.2.12", "2"};
        for (int i = 0; i < paths.length; i++) {
            Repository repo;
            try (InputStream input = Files.newInputStream(Path.of(args[i + 1]))) {
                repo = (Repository) SchemaModuleUtil.unmarshal(input, AndroidSdkHandler.getAllModules(),
                    true, NullProgressIndicator.INSTANCE, args[i + 1]);
            }
            LocalPackage pkg = repo.getLocalPackage();
            if (pkg == null || !paths[i].equals(pkg.getPath()) || !versions[i].equals(pkg.getVersion().toString())
                || !"android-sdk-license".equals(pkg.getLicense().getId())
                || !"24333f8a63b6825ea9c5514f83c2829b004d1fee".equals(pkg.getLicense().getLicenseHash()))
                throw new IllegalStateException("Fixed package/license metadata mismatch");
            if (!pkg.getLicense().checkAccepted(host)) {
                System.out.println("NOT RUN: no existing accepted license on installer host; no SDK payload installed. Terms: https://developer.android.com/studio/terms");
                System.exit(3);
            }
            if (args.length == 4 && "discover".equals(args[3])) {
                RepoManager manager = RepoManager.createRepoManager(host, AndroidSdkHandler.getAllModules(),
                    Collections.emptyList(), null, null);
                manager.reloadLocalIfNeeded(NullProgressIndicator.INSTANCE);
                LocalPackage found = manager.getPackages().getLocalPackages().get(paths[i]);
                if (found == null || !found.getVersion().equals(pkg.getVersion())
                    || !found.getTypeDetails().getClass().equals(pkg.getTypeDetails().getClass()))
                    throw new IllegalStateException("Fixed installed package discovery failed");
            }
            System.out.println("PASS: existing accepted license and fixed metadata: " + paths[i]);
        }
    }
}
