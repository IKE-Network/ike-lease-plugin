/**
 * The working-set lease core: the lease protocol (the exact, golden-tested
 * port of {@code lease.sh} v2 — IKE-Network/ike-issues#1067) and the
 * git-state materializer (IKE-Network/ike-issues#1057), with the CLIs the
 * {@code lease.sh} wrapper execs.
 *
 * <p>Deliberately IDE-free: the IntelliJ plugin is one thin host of this
 * module, the headless CLI another, and Maven-side consumers (the
 * {@code ws:} goals' lease client, IKE-Network/ike-issues#1005) a third.
 */
module network.ike.lease.core {
    requires java.net.http;

    exports network.ike.lease.core;
}
