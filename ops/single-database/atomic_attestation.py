"""Publish complete attestation JSON atomically, without replacing any existing name."""
import ctypes, errno, json, os, stat, sys, uuid

def _rename_noreplace(fd, source, target):
    libc = ctypes.CDLL(None, use_errno=True)
    if sys.platform == 'linux':
        call = libc.renameat2
        flags = 1  # Linux RENAME_NOREPLACE
    elif sys.platform == 'darwin':
        call = libc.renameatx_np
        flags = 4  # Darwin RENAME_EXCL (sys/stdio.h)
    else:
        raise OSError(errno.ENOTSUP, 'ATOMIC_NOREPLACE_UNSUPPORTED')
    call.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    call.restype = ctypes.c_int
    if call(fd, source.encode(), fd, target.encode(), flags) != 0:
        code = ctypes.get_errno()
        raise OSError(code, 'ATOMIC_NOREPLACE_REFUSED')

def publish(parent, value):
    payload = (json.dumps(value) + '\n').encode('utf-8')
    if len(payload) > 65536:
        raise ValueError('ATTESTATION_SIZE')
    directory = os.open(parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    temp = '.attestation-' + str(uuid.uuid4()) + '.tmp'
    try:
        metadata = os.fstat(directory)
        if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
            raise ValueError('ATTESTATION_PARENT')
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
        try:
            view = memoryview(payload)
            while view:
                written = os.write(fd, view)
                if written <= 0:
                    raise OSError('ATTESTATION_SHORT_WRITE')
                view = view[written:]
            os.fchmod(fd, 0o644)
            os.fsync(fd)
        finally:
            os.close(fd)
        _rename_noreplace(directory, temp, 'attestation.json')
        os.fsync(directory)
        final = os.stat('attestation.json', dir_fd=directory, follow_symlinks=False)
        if not stat.S_ISREG(final.st_mode) or final.st_nlink != 1 or final.st_uid != os.geteuid() or stat.S_IMODE(final.st_mode) != 0o644:
            raise ValueError('ATTESTATION_FINAL_METADATA')
    finally:
        try:
            os.unlink(temp, dir_fd=directory)
        except FileNotFoundError:
            pass
        os.close(directory)
    return payload
