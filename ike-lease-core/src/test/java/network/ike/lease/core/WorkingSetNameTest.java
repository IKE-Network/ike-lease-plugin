package network.ike.lease.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Classification and derivation of working-set names. */
class WorkingSetNameTest {

    @Test
    void rootNameIsNotASibling() {
        WorkingSetName name = new WorkingSetName("ike-tooling");
        assertFalse(name.isSibling());
        assertThrows(IllegalStateException.class, name::parent);
        assertThrows(IllegalStateException.class, name::feature);
    }

    @Test
    void siblingNameSplitsAtTheSeparator() {
        WorkingSetName name = new WorkingSetName("ike-komet-wsr꞉issue-969");
        assertTrue(name.isSibling());
        assertEquals("ike-komet-wsr", name.parent().value());
        assertEquals("issue-969", name.feature());
    }

    @Test
    void featureBranchUsesTheCreationDerivation() {
        // ws:feature-start-sibling: branchName = "feature/" + feature.
        assertEquals("feature/issue-969",
                new WorkingSetName("ike-komet-wsr꞉issue-969").featureBranch());
    }

    @Test
    void blankAndPathNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkingSetName(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkingSetName("a/b"));
    }

    @Test
    void emptyParentOrFeatureHalvesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkingSetName("꞉feature"));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkingSetName("parent꞉"));
    }
}
