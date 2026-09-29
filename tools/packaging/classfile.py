"""Read the defining class name, including for opaque bytecode resources."""
import struct


def class_name(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('invalid classfile magic')
    count = struct.unpack_from('>H', data, 8)[0]
    pool = [None] * count
    offset, index = 10, 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            pool[index] = data[offset:offset + size]
            offset += size
        elif tag == 7:
            pool[index] = struct.unpack_from('>H', data, offset)[0]
            offset += 2
        elif tag in sizes:
            offset += sizes[tag]
            if tag in (5, 6):
                index += 1
        else:
            raise ValueError('unsupported classfile constant pool tag')
        index += 1
    this_class = struct.unpack_from('>H', data, offset + 2)[0]
    return pool[pool[this_class]].decode('utf-8')
