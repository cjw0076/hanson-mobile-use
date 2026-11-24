#!/usr/bin/env python3
"""Multi-Device Session Isolation, Lease Reservations & Dirty-Device Recovery.

Absorbed from `google/device-infra` (`LocalDeviceRunner.java`, `SimpleScheduler.java`,
`DeviceStateChecker.java`) and `quern-dev/quern` (`docs/agent-guide/devices/pool.md`).

Guarantees (100% Python 3.9+ stdlib, zero external pip deps):
1. Session-keyed active-device isolation: refuses ambiguous multi-device calls when
   more than one device is attached unless a serial is pinned or leased.
2. TTL `DeviceReservation` leases with `is_allocated()` guard preventing background
   cleanups from clobbering active sessions.
3. Rotational fair scheduler (`schedule_next` / `schedule_round_robin`) across devices.
4. Dirty-device tracking (`is_dirty()`) & post-run cleanup (`force-stop` test packages,
   IME reset, system dialog dismissal).
"""
from __future__ import annotations

import subprocess
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Sequence, Set, Tuple


class AmbiguousDeviceError(RuntimeError):
    """Raised when multiple devices are attached and no session lease/serial pin is active."""


class DeviceBusyError(RuntimeError):
    """Raised when attempting to acquire or clean an actively allocated device."""


@dataclass
class DeviceReservation:
    serial: str
    session_id: str
    acquired_at: float
    ttl_seconds: float = 300.0
    released: bool = False
    touched_packages: Set[str] = field(default_factory=set)

    def is_expired(self, now: Optional[float] = None) -> bool:
        t = time.time() if now is None else float(now)
        return (t - self.acquired_at) >= self.ttl_seconds

    def is_allocated(self, now: Optional[float] = None) -> bool:
        return (not self.released) and (not self.is_expired(now=now))


class HansonDevicePool:
    """Session-isolated Android/iOS device pool with TTL leases and rotational fair scheduling."""

    def __init__(
        self,
        device_lister: Optional[Callable[[], Sequence[str]]] = None,
        clock: Callable[[], float] = time.time,
        command_runner: Optional[Callable[[str, List[str]], Tuple[int, str]]] = None,
    ):
        self._lister = device_lister
        self._clock = clock
        self._runner = command_runner
        self._reservations: Dict[str, DeviceReservation] = {}  # serial -> active reservation
        self._session_pins: Dict[str, str] = {}                # session_id -> serial
        self._dirty_packages: Dict[str, Set[str]] = {}         # serial -> packages needing cleanup
        self._dirty_flags: Set[str] = set()
        self._rr_cursor: int = 0

    def list_devices(self) -> List[str]:
        if self._lister is not None:
            return [str(s).strip() for s in self._lister() if str(s).strip()]
        proc = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=8, check=False)
        out: List[str] = []
        for line in (proc.stdout or "").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                out.append(parts[0])
        return out

    def _evict_expired(self) -> None:
        now = self._clock()
        expired_serials = [s for s, r in self._reservations.items() if not r.is_allocated(now=now)]
        for serial in expired_serials:
            res = self._reservations.pop(serial)
            if self._session_pins.get(res.session_id) == serial:
                self._session_pins.pop(res.session_id, None)

    def is_allocated(self, serial: str) -> bool:
        self._evict_expired()
        res = self._reservations.get(serial)
        return bool(res and res.is_allocated(now=self._clock()))

    def pin_session(self, session_id: str, serial: str) -> None:
        sid = (session_id or "").strip()
        ser = (serial or "").strip()
        if not sid or not ser:
            raise ValueError("session_id and serial must be non-empty")
        self._session_pins[sid] = ser

    def acquire(
        self,
        session_id: str,
        serial: Optional[str] = None,
        ttl_seconds: float = 300.0,
    ) -> DeviceReservation:
        self._evict_expired()
        sid = (session_id or "").strip()
        if not sid:
            raise ValueError("session_id must be non-empty")

        available = self.list_devices()
        target = (serial or self._session_pins.get(sid) or "").strip()
        if not target:
            idle = [d for d in available if d not in self._reservations]
            if not idle:
                raise DeviceBusyError("No idle devices available in HansonDevicePool.")
            if len(idle) > 1:
                raise AmbiguousDeviceError(
                    f"Multiple idle devices attached ({idle}) for session '{sid}'. "
                    "Pass an explicit serial, call pin_session(), or use schedule_round_robin()."
                )
            target = idle[0]

        existing = self._reservations.get(target)
        if existing and existing.is_allocated(now=self._clock()) and existing.session_id != sid:
            raise DeviceBusyError(
                f"Device '{target}' is currently leased to session '{existing.session_id}'."
            )

        res = DeviceReservation(
            serial=target,
            session_id=sid,
            acquired_at=self._clock(),
            ttl_seconds=max(1.0, float(ttl_seconds)),
        )
        self._reservations[target] = res
        self._session_pins[sid] = target
        return res

    def release(self, session_id: str, mark_dirty: bool = True) -> Optional[DeviceReservation]:
        self._evict_expired()
        serial = self._session_pins.pop(session_id, None)
        if not serial:
            return None
        res = self._reservations.pop(serial, None)
        if res is not None:
            res.released = True
            if mark_dirty or res.touched_packages:
                self.mark_dirty(serial, packages=res.touched_packages)
        return res

    def resolve_serial(self, session_id: str, explicit_serial: Optional[str] = None) -> str:
        """Resolve the device serial for `session_id`, refusing ambiguous multi-device states."""
        self._evict_expired()
        if explicit_serial and explicit_serial.strip():
            return explicit_serial.strip()
        pinned = self._session_pins.get(session_id)
        if pinned:
            return pinned
        devices = self.list_devices()
        if not devices:
            raise RuntimeError("No attached devices found in HansonDevicePool.")
        if len(devices) == 1:
            return devices[0]
        raise AmbiguousDeviceError(
            f"Ambiguous multi-device call for session '{session_id}': {devices}. "
            "Acquire a lease or pin a specific serial."
        )

    def schedule_round_robin(self, job_ids: Sequence[str], ttl_seconds: float = 300.0) -> List[DeviceReservation]:
        """Rotational fair scheduler: assigns pending jobs across idle devices in round-robin order."""
        self._evict_expired()
        devices = self.list_devices()
        if not devices:
            return []
        rotated = devices[self._rr_cursor % len(devices):] + devices[: self._rr_cursor % len(devices)]
        idle = [d for d in rotated if d not in self._reservations]
        leases: List[DeviceReservation] = []
        for job_id, serial in zip(job_ids, idle):
            leases.append(self.acquire(session_id=job_id, serial=serial, ttl_seconds=ttl_seconds))
            self._rr_cursor = (self._rr_cursor + 1) % len(devices)
        return leases

    def mark_dirty(self, serial: str, packages: Optional[Sequence[str]] = None) -> None:
        self._dirty_flags.add(serial)
        if packages:
            self._dirty_packages.setdefault(serial, set()).update(p for p in packages if p)

    def is_dirty(self, serial: str) -> bool:
        return serial in self._dirty_flags or bool(self._dirty_packages.get(serial))

    def _run_device_cmd(self, serial: str, args: List[str]) -> Tuple[int, str]:
        if self._runner is not None:
            return self._runner(serial, args)
        proc = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=10, check=False)
        return proc.returncode, (proc.stdout or proc.stderr or "").strip()

    def cleanup_dirty_device(self, serial: str) -> Dict[str, Any]:
        """Post-run dirty-device cleanup (`force-stop` tracked packages + IME/dialog reset).

        Strictly honors `is_allocated()` guard so active sessions are never clobbered.
        """
        if self.is_allocated(serial):
            raise DeviceBusyError(
                f"Refusing dirty cleanup on '{serial}': device has an active allocation."
            )
        if not self.is_dirty(serial):
            return {"serial": serial, "cleaned": False, "reason": "clean", "commands": []}

        executed: List[List[str]] = []
        for pkg in sorted(self._dirty_packages.get(serial, set())):
            cmd = ["shell", "am", "force-stop", pkg]
            self._run_device_cmd(serial, cmd)
            executed.append(cmd)

        for hygiene_cmd in (
            ["shell", "am", "broadcast", "-a", "android.intent.action.CLOSE_SYSTEM_DIALOGS"],
            ["shell", "input", "keyevent", "KEYCODE_HOME"],
        ):
            self._run_device_cmd(serial, hygiene_cmd)
            executed.append(hygiene_cmd)

        self._dirty_flags.discard(serial)
        self._dirty_packages.pop(serial, None)
        return {"serial": serial, "cleaned": True, "commands": executed}
