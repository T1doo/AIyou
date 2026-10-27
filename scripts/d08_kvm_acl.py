"""D08-only reversible KVM grant. No command executes on import.

Existing masks are never widened. A mask created from a trivial rw group ACL
belongs to this job and is removed with the new UID, after exact identity checks.
"""
import os
from pathlib import Path
import stat
from d02_kvm_acl import parse_acl, effective_others


def grant_plan(before, uid):
    key = "user:" + str(uid)
    if key in before:
        raise RuntimeError("Pre-existing UID ACL is not owned by this job")
    if "mask:" in before:
        if before["mask:"] & 6 != 6:
            raise RuntimeError("Refuse to widen an existing mask")
        created_mask = False
    else:
        if before["group:"] != 6 or any(k not in {"user:", "group:", "other:"} for k in before):
            raise RuntimeError("New mask requires a trivial group::rw- ACL")
        created_mask = True
    after = {**before, key: 6}
    if created_mask:
        after["mask:"] = 6
    if effective_others(before, uid) != effective_others(after, uid):
        raise RuntimeError("Other principals' effective access would change")
    return after, created_mask


class RecoveryKvmAcl:
    def __init__(self, command, report, path=Path("/dev/kvm")):
        self.command, self.report, self.path = command, report, path
        self.uid = os.getuid()
        self.before = self.after = self.identity = None
        self.changed = self.created_mask = False

    def node(self):
        if self.path.is_symlink():
            raise RuntimeError("KVM symlink refused")
        s = self.path.stat()
        if not stat.S_ISCHR(s.st_mode):
            raise RuntimeError("Existing KVM character device required")
        return (s.st_dev, s.st_ino, s.st_rdev, s.st_uid, s.st_gid)

    def read(self, name):
        return parse_acl(self.command(["getfacl", "-c", "-p", "-n", self.path], name).stdout)

    def preflight(self):
        self.identity = self.node()
        self.before = self.read("kvm-before")
        self.report["kvm_before"] = self.before
        self.report["kvm_node"] = self.identity
        if os.access(self.path, os.R_OK | os.W_OK):
            self.report["kvm_changed"] = False
            return
        if self.identity[3] == self.uid:
            raise RuntimeError("Named ACL cannot override owner access")
        self.after, self.created_mask = grant_plan(self.before, self.uid)

    def grant(self):
        if self.identity is None:
            raise RuntimeError("Read-only KVM preflight required")
        if self.node() != self.identity or self.read("kvm-admission") != self.before:
            raise RuntimeError("KVM changed before admission")
        if self.after is None:
            if not os.access(self.path, os.R_OK | os.W_OK):
                raise RuntimeError("Previously available KVM access disappeared")
            return
        acl = "u:" + str(self.uid) + ":rw-" + (",m::rw-" if self.created_mask else "")
        # Set before the command: partial command success must enter cleanup.
        self.changed = True
        self.command(["sudo", "-n", "setfacl", "--no-mask", "-m", acl, self.path], "kvm-grant")
        if self.node() != self.identity or self.read("kvm-after") != self.after or not os.access(self.path, os.R_OK | os.W_OK):
            raise RuntimeError("Exact KVM grant was not confirmed")
        self.report.update(kvm_changed=True, kvm_created_mask=self.created_mask, kvm_after=self.after)

    def restore(self):
        if not self.changed:
            self.report["kvm_cleanup"] = "NOT NEEDED"
            return
        if self.node() != self.identity:
            raise RuntimeError("KVM identity changed; no cleanup mutation")
        current = self.read("kvm-cleanup-before")
        self.report["kvm_cleanup_observed"] = current
        if current == self.before:
            self.report["kvm_cleanup"] = "PASS: already exact original ACL"
            return
        if current != self.after:
            raise RuntimeError("External ACL change; no cleanup mutation")
        removal = "u:" + str(self.uid) + (",m:" if self.created_mask else "")
        self.command(["sudo", "-n", "setfacl", "--no-mask", "-x", removal, self.path], "kvm-withdraw")
        if self.node() != self.identity or self.read("kvm-cleanup-after") != self.before:
            raise RuntimeError("Exact original ACL restoration unconfirmed")
        self.report["kvm_cleanup"] = "PASS: exact original ACL; only owned UID/mask removed"
