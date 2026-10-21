package network.ike.lease;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Settings → Tools → IKE Build Lock: the switch for
 * {@link BuildLockSettings}.
 */
public final class BuildLockConfigurable implements Configurable {

    private JCheckBox enabled;

    /** Creates the page; the platform instantiates this reflectively. */
    public BuildLockConfigurable() { }

    @Override
    public @Nls String getDisplayName() {
        return "IKE Build Lock";
    }

    @Override
    public JComponent createComponent() {
        enabled = new JCheckBox("Take the working set's build lock around IDE compiles");
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(enabled);
        panel.add(new JLabel("<html>An IDE compile waits while a Maven build of the same tree holds "
                + "<code>.mvn/target/build.lock</code>, and a Maven build waits while the IDE compiles. "
                + "Only working sets whose <code>.mvn/extensions.xml</code> loads "
                + "ike-workspace-extension are locked. The lock covers the compile only, "
                + "never a run or debug session.</html>"));
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return enabled != null && enabled.isSelected() != BuildLockSettings.getInstance().isEnabled();
    }

    @Override
    public void apply() {
        BuildLockSettings.getInstance().setEnabled(enabled.isSelected());
    }

    @Override
    public void reset() {
        if (enabled != null) {
            enabled.setSelected(BuildLockSettings.getInstance().isEnabled());
        }
    }

    @Override
    public void disposeUIResources() {
        enabled = null;
    }
}
