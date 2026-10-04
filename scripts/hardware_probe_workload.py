"""Small native CPU/CUDA/ARIES access probes; never claims model acceptance."""
import ctypes as c
import json
import os
from pathlib import Path
import platform
import stat
import sys


class DriverError(Exception):
    def __init__(self,function,code):self.function,self.code=function,code


def cuda():
    driver=c.CDLL('libcuda.so.1')
    def invoke(name,types,*values):
        f=getattr(driver,name);f.argtypes=types;f.restype=c.c_int
        code=f(*values)
        if code:raise DriverError(name,code)
    invoke('cuInit',[c.c_uint],0)
    count=c.c_int();invoke('cuDeviceGetCount',[c.POINTER(c.c_int)],c.byref(count))
    if count.value!=1:raise AssertionError('Exactly one allocated CUDA device required')
    device=c.c_int();invoke('cuDeviceGet',[c.POINTER(c.c_int),c.c_int],c.byref(device),0)
    name=c.create_string_buffer(128);invoke('cuDeviceGetName',[c.c_void_p,c.c_int,c.c_int],name,128,device)
    version=c.c_int();invoke('cuDriverGetVersion',[c.POINTER(c.c_int)],c.byref(version))
    context=c.c_void_p();module=c.c_void_p();source=c.c_uint64();target=c.c_uint64()
    retained=False
    try:
        invoke('cuDevicePrimaryCtxRetain',[c.POINTER(c.c_void_p),c.c_int],c.byref(context),device);retained=True
        invoke('cuCtxSetCurrent',[c.c_void_p],context)
        data=(c.c_float*4)(2,4,16,128);output=(c.c_float*4)();size=c.sizeof(data)
        for pointer in (source,target):invoke('cuMemAlloc_v2',[c.POINTER(c.c_uint64),c.c_size_t],c.byref(pointer),size)
        invoke('cuMemcpyHtoD_v2',[c.c_uint64,c.c_void_p,c.c_size_t],source,data,size)
        ptx=c.create_string_buffer(b'''.version 6.0
.target sm_50
.address_size 64
.visible .entry affine(.param .u64 input, .param .u64 output) {
 .reg .b32 %r;
 .reg .b64 %a, %b, %offset, %src, %dst;
 .reg .f32 %x, %y;
 ld.param.u64 %a, [input];
 ld.param.u64 %b, [output];
 mov.u32 %r, %tid.x;
 mul.wide.u32 %offset, %r, 4;
 add.u64 %src, %a, %offset;
 add.u64 %dst, %b, %offset;
 ld.global.f32 %x, [%src];
 fma.rn.f32 %y, %x, 0f40000000, 0f3F800000;
 st.global.f32 [%dst], %y;
 ret;
}
''')
        invoke('cuModuleLoadData',[c.POINTER(c.c_void_p),c.c_void_p],c.byref(module),ptx)
        function=c.c_void_p();invoke('cuModuleGetFunction',[c.POINTER(c.c_void_p),c.c_void_p,c.c_char_p],c.byref(function),module,b'affine')
        parameters=(c.c_void_p*2)(c.addressof(source),c.addressof(target))
        invoke('cuLaunchKernel',[c.c_void_p]+[c.c_uint]*7+[c.c_void_p,c.POINTER(c.c_void_p),c.POINTER(c.c_void_p)],
            function,1,1,1,4,1,1,0,None,parameters,None)
        invoke('cuCtxSynchronize',[])
        invoke('cuMemcpyDtoH_v2',[c.c_void_p,c.c_uint64,c.c_size_t],output,target,size)
        result=list(output)
        if result!=[5.0,9.0,33.0,257.0]:raise AssertionError('CUDA calculation differs')
        return {'deviceCount':count.value,'deviceName':name.value.decode(),'driverVersion':version.value,
            'kernel':'y=2*x+1','input':list(data),'output':result,'deviceBytesAllocated':size*2}
    finally:
        for pointer in (source,target):
            if pointer.value:invoke('cuMemFree_v2',[c.c_uint64],pointer)
        if module.value:invoke('cuModuleUnload',[c.c_void_p],module)
        if retained:invoke('cuDevicePrimaryCtxRelease_v2',[c.c_int],device)


def aries():
    devices=[]
    for path in sorted(Path('/dev').glob('aries*')):
        if not stat.S_ISCHR(path.stat().st_mode):continue
        handle=os.open(path,os.O_RDWR|os.O_NONBLOCK)
        os.close(handle);devices.append(path.name)
    if not devices:raise AssertionError('Allocated ARIES character device missing')
    return {'devices':devices,'nonRootOpenCloseVerified':True,'inferenceVerified':False}


def main():
    kind=sys.argv[1];result={'scope':'native-hardware-component','probe':kind,'machine':platform.machine(),
        'uid':os.getuid(),'sourceMode':'SYNTHETIC','modelAcceptanceVerified':False}
    code=0
    try:
        if os.getuid()==0:raise AssertionError('Probe must run without root')
        if kind=='cuda':result.update(cuda())
        elif kind=='aries':result.update(aries())
        elif kind=='cpu':
            result['output']=[2*x+1 for x in (2,4,16,128)]
            assert result['output']==[5,9,33,257]
        else:raise ValueError('Unknown hardware probe')
        result['status']='PASS'
    except Exception as error:
        code=1;result.update(status='FAIL',failureType=type(error).__name__)
        if isinstance(error,DriverError):result.update(function=error.function,driverCode=error.code)
        if isinstance(error,OSError):result['errno']=error.errno
    print(json.dumps(result),flush=True)
    return code


if __name__=='__main__':raise SystemExit(main())
