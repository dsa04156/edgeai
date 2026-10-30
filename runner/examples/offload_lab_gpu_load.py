"""Offload test console GPU load: keeps the allocated CUDA device busy for a bounded number of seconds, then exits.

Only for creating real node GPU pressure on one chosen edge node; it computes nothing useful, is not a benchmark and
never falls back to CPU. CUDA Driver API through ctypes (libcuda.so.1 is supplied by the NVIDIA device plugin/runtime):
a fixed PTX busy-arithmetic kernel is launched repeatedly, each launch calibrated to about LAUNCH_SECONDS so SIGTERM and
the deadline are checked between short launches. Prints one JSON line per event; exits nonzero when CUDA is unavailable.
"""
import argparse
import ctypes as c
import json
import signal
import sys
import time

LAUNCH_SECONDS = 0.05
THREADS = 256
BLOCKS_PER_SM = 8
MULTIPROCESSOR_COUNT = 16  # CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT
MIN_ITERATIONS, MAX_ITERATIONS = 1 << 10, 1 << 26
PTX = b'''.version 6.0
.target sm_50
.address_size 64
.visible .entry burn(.param .u64 out, .param .u32 iterations) {
 .reg .pred %p;
 .reg .b32 %i, %n, %t, %nt, %ct, %idx;
 .reg .b64 %o, %off;
 .reg .f32 %x, %y;
 ld.param.u64 %o, [out];
 ld.param.u32 %n, [iterations];
 mov.u32 %t, %tid.x;
 mov.u32 %nt, %ntid.x;
 mov.u32 %ct, %ctaid.x;
 mad.lo.u32 %idx, %ct, %nt, %t;
 cvt.rn.f32.u32 %x, %idx;
 mov.f32 %y, 0f3F800000;
 mov.u32 %i, 0;
LOOP:
 setp.ge.u32 %p, %i, %n;
 @%p bra DONE;
 fma.rn.f32 %x, %x, 0f3F7FFFFE, %y;
 fma.rn.f32 %y, %y, 0f3F7FFFFE, %x;
 add.u32 %i, %i, 1;
 bra LOOP;
DONE:
 add.f32 %x, %x, %y;
 mul.wide.u32 %off, %idx, 4;
 add.u64 %o, %o, %off;
 st.global.f32 [%o], %x;
 ret;
}
'''


class DriverError(Exception):
    def __init__(self, function, code):
        super().__init__(f"{function} returned {code}")
        self.function, self.code = function, code


def emit(event, **fields):
    print(json.dumps({"event": event, **fields}), flush=True)


def burn(driver, seconds, stopped):
    def invoke(name, types, *values):
        function = getattr(driver, name)
        function.argtypes, function.restype = types, c.c_int
        code = function(*values)
        if code:
            raise DriverError(name, code)

    invoke("cuInit", [c.c_uint], 0)
    count = c.c_int()
    invoke("cuDeviceGetCount", [c.POINTER(c.c_int)], c.byref(count))
    if count.value < 1:
        raise DriverError("cuDeviceGetCount", 100)  # CUDA_ERROR_NO_DEVICE
    device = c.c_int()
    invoke("cuDeviceGet", [c.POINTER(c.c_int), c.c_int], c.byref(device), 0)
    name = c.create_string_buffer(128)
    invoke("cuDeviceGetName", [c.c_void_p, c.c_int, c.c_int], name, 128, device)
    sms = c.c_int()
    invoke("cuDeviceGetAttribute", [c.POINTER(c.c_int), c.c_int, c.c_int], c.byref(sms), MULTIPROCESSOR_COUNT, device)
    context, module, buffer = c.c_void_p(), c.c_void_p(), c.c_uint64()
    retained = False
    try:
        invoke("cuDevicePrimaryCtxRetain", [c.POINTER(c.c_void_p), c.c_int], c.byref(context), device)
        retained = True
        invoke("cuCtxSetCurrent", [c.c_void_p], context)
        blocks = max(1, sms.value) * BLOCKS_PER_SM
        invoke("cuMemAlloc_v2", [c.POINTER(c.c_uint64), c.c_size_t], c.byref(buffer), blocks * THREADS * 4)
        invoke("cuModuleLoadData", [c.POINTER(c.c_void_p), c.c_void_p], c.byref(module), c.create_string_buffer(PTX))
        kernel = c.c_void_p()
        invoke("cuModuleGetFunction", [c.POINTER(c.c_void_p), c.c_void_p, c.c_char_p], c.byref(kernel), module, b"burn")
        iterations = c.c_uint32(MIN_ITERATIONS)
        parameters = (c.c_void_p * 2)(c.addressof(buffer), c.addressof(iterations))
        launch_types = [c.c_void_p] + [c.c_uint] * 7 + [c.c_void_p, c.POINTER(c.c_void_p), c.POINTER(c.c_void_p)]
        emit("gpu-load-started", device=name.value.decode(errors="replace"), multiprocessors=sms.value,
             blocks=blocks, threadsPerBlock=THREADS, seconds=seconds)
        started = time.monotonic()
        deadline = started + seconds
        launches = 0
        while not stopped() and time.monotonic() < deadline:
            launch_started = time.monotonic()
            invoke("cuLaunchKernel", launch_types, kernel, blocks, 1, 1, THREADS, 1, 1, 0, None, parameters, None)
            invoke("cuCtxSynchronize", [])
            launches += 1
            elapsed = time.monotonic() - launch_started
            if elapsed < LAUNCH_SECONDS / 2:
                iterations.value = min(MAX_ITERATIONS, iterations.value * 2)
            elif elapsed > LAUNCH_SECONDS * 2:
                iterations.value = max(MIN_ITERATIONS, iterations.value // 2)
        emit("gpu-load-stopped", reason="signal" if stopped() else "deadline", launches=launches,
             iterationsPerLaunch=iterations.value, elapsedSeconds=round(time.monotonic() - started, 3))
    finally:
        if buffer.value:
            invoke("cuMemFree_v2", [c.c_uint64], buffer)
        if module.value:
            invoke("cuModuleUnload", [c.c_void_p], module)
        if retained:
            invoke("cuDevicePrimaryCtxRelease_v2", [c.c_int], device)


def main(argv=None, load_driver=lambda: c.CDLL("libcuda.so.1")):
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=int, required=True)
    args = parser.parse_args(argv)
    if not 1 <= args.seconds <= 1800:
        parser.error("seconds 1..1800")
    received = []
    signal.signal(signal.SIGTERM, lambda *_: received.append(True))
    try:
        burn(load_driver(), args.seconds, lambda: bool(received))
    except Exception as error:
        failure = {"failureType": type(error).__name__, "message": str(error)}
        if isinstance(error, DriverError):
            failure.update(function=error.function, driverCode=error.code)
        emit("gpu-load-failed", **failure)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
