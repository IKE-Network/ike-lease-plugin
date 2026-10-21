package network.ike.lease;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * Whether IDE compiles take the per-tree build lock
 * (IKE-Network/ike-issues#1315). On by default; turned off under
 * Settings → Tools → IKE Build Lock. Per machine, like every IDE setting.
 */
@Service(Service.Level.APP)
@State(name = "IkeBuildLock", storages = @Storage("ike-lease.xml"))
public final class BuildLockSettings implements PersistentStateComponent<BuildLockSettings.Settings> {

    /** The stored form. */
    public static final class Settings {
        /** Whether compiles take the lock. */
        public boolean enabled = true;

        /** Creates the default settings; the platform instantiates this reflectively. */
        public Settings() { }
    }

    private Settings settings = new Settings();

    /** Creates the service; the platform instantiates this on first use. */
    public BuildLockSettings() { }

    /**
     * The application's instance.
     *
     * @return the settings service
     */
    public static BuildLockSettings getInstance() {
        return ApplicationManager.getApplication().getService(BuildLockSettings.class);
    }

    /**
     * Whether compiles take the lock.
     *
     * @return {@code true} unless turned off
     */
    public boolean isEnabled() {
        return settings.enabled;
    }

    /**
     * Turns the lock on or off for compiles from now on.
     *
     * @param enabled whether compiles take the lock
     */
    public void setEnabled(boolean enabled) {
        settings.enabled = enabled;
    }

    @Override
    public @NotNull Settings getState() {
        return settings;
    }

    @Override
    public void loadState(@NotNull Settings state) {
        settings = state;
    }
}
