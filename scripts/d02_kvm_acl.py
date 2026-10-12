"""Authorized single-job KVM ACL only; refuse effective access changes to others."""
import os
from pathlib import Path
import stat


def permissions(value):
    if len(value) != 3 or any(c not in allowed for c, allowed in zip(value, ("r-", "w-", "x-"))):
        raise ValueError("Invalid ACL permission")
    return sum(bit for char, bit in zip(value, (4, 2, 1)) if char != "-")


def render(bits):
    return "".join(c if bits & b else "-" for c, b in zip("rwx", (4, 2, 1)))


def parse_acl(text):
    acl = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        record = line.split("#", 1)[0].strip().split(":")
        if len(record) != 3 or record[0] not in ("user", "group", "mask", "other") or \
                (record[1] and not record[1].isdigit()) or \
                (record[0] in ("mask", "other") and record[1]):
            raise ValueError("Unexpected numeric KVM ACL format")
        key = ":".join(record[:2])
        if key in acl:
            raise ValueError("Duplicate ACL entry")
        acl[key] = permissions(record[2])
    if not {"user:", "group:", "other:"}.issubset(acl):
        raise ValueError("Missing base ACL entries")
    return acl


def effective_others(acl, uid):
    mask = acl.get("mask:", 7)
    return {key: bits if key in ("user:", "other:") else bits & mask
            for key, bits in acl.items() if key not in ("user:" + str(uid), "mask:")}


def proposed_acl(original, uid):
    new = {**original, "user:" + str(uid): 6, "mask:": original.get("mask:", original["group:"]) | 6}
    if effective_others(original, uid) != effective_others(new, uid):
        raise RuntimeError("Grant would expand/change another KVM principal's effective access")
    return new


class OwnedKvmAcl:
    def __init__(self, command, report):
        self.command, self.report = command, report
        self.uid, self.path = os.getuid(), Path("/dev/kvm")
        self.before = self.after = None
        self.identity = None
        self.changed = False

    def read(self, label, cleanup=False):
        return parse_acl(self.command(["getfacl", "-c", "-p", "-n", self.path], label,
                                     deadline_bound=not cleanup, timeout=15).stdout)

    def grant(self):
        # No node creation, driver installation, group/udev/global mode change.
        if self.path.is_symlink() or not self.path.exists():
            raise RuntimeError("KVM node absent/indirect; authorized ACL cannot create acceleration")
        node = self.path.stat()
        if not stat.S_ISCHR(node.st_mode):
            raise RuntimeError("KVM path is not a character device")
        self.identity = (node.st_dev, node.st_ino, node.st_rdev, node.st_uid, node.st_gid)
        self.report["kvm_node_identity"] = list(self.identity)
        self.report["kvm_initial_read_write"] = os.access(self.path, os.R_OK | os.W_OK)
        if self.report["kvm_initial_read_write"]:
            self.report["kvm_acl_changed"] = False
            return
        if node.st_uid == self.uid:
            raise RuntimeError("Current UID owns inaccessible KVM; named ACL cannot override owner")
        self.before = self.read("kvm-acl-before")
        if "user:" + str(self.uid) in self.before:
            raise RuntimeError("Existing current-UID ACL entry cannot be attributed to this job; no permission change")
        self.after = proposed_acl(self.before, self.uid)
        self.report["kvm_acl_before"] = self.before
        # Mark before mutating: even a post-command validation failure must restore.
        self.changed = True
        self.command(["sudo", "-n", "setfacl", "--no-mask", "-m",
                      "u:" + str(self.uid) + ":rw-,m::" + render(self.after["mask:"]), self.path],
                     "kvm-grant-current-uid", timeout=15)
        actual = self.read("kvm-acl-after")
        if actual != self.after or not os.access(self.path, os.R_OK | os.W_OK):
            raise RuntimeError("Exact current-UID ACL grant not effective")
        self.report.update(kvm_acl_changed=True, kvm_uid=self.uid, kvm_acl_after=actual)

    def restore(self):
        if not self.changed:
            self.report["kvm_acl_restored"] = "NOT NEEDED"
            return
        node = self.path.stat()
        if self.identity != (node.st_dev, node.st_ino, node.st_rdev, node.st_uid, node.st_gid):
            raise RuntimeError("KVM identity changed; cannot modify unknown replacement")
        current = self.read("kvm-acl-before-restore", cleanup=True)
        self.report["kvm_acl_observed_at_cleanup"] = current
        self.report["kvm_other_effective_access_unchanged"] = effective_others(current, self.uid) == effective_others(self.before, self.uid)
        self.report["kvm_cleanup_scope"] = "ONLY_NEW_CURRENT_UID_ENTRY; MASK_UNTOUCHED"
        if current == self.before:
            self.report["kvm_acl_restored"] = True
            return
        if current != self.after:
            self.report["kvm_acl_restored"] = False
            self.report["kvm_owned_uid_present_at_cleanup"] = "user:" + str(self.uid) in current
            raise RuntimeError("KVM ACL changed externally; refuse whole-ACL overwrite")
        key = "user:" + str(self.uid)
        if key in self.before:
            raise RuntimeError("Current-UID ACL predates job; cannot withdraw it")
        self.command(["sudo", "-n", "setfacl", "--no-mask", "-x", "u:" + str(self.uid), self.path],
                     "kvm-remove-owned-uid", deadline_bound=False, timeout=15)
        actual = self.read("kvm-acl-after-owned-uid-withdrawal", cleanup=True)
        expected = {k: v for k, v in current.items() if k != key}
        self.report["kvm_acl_after_cleanup"] = actual
        self.report["kvm_acl_restored"] = actual == self.before
        self.report["kvm_owned_uid_removed"] = key not in actual
        self.report["kvm_other_effective_access_unchanged"] = effective_others(actual, self.uid) == effective_others(self.before, self.uid)
        if actual != expected or not self.report["kvm_other_effective_access_unchanged"]:
            raise RuntimeError("ACL changed during owned-UID withdrawal; no further permission action")
        self.report["kvm_scoped_cleanup"] = "PASS: only job-added UID removed; remaining ACL/mask untouched"
