"""Summarise a btsnoop HCI log: the LE commands the phone sent (advertising, scanning, connections) and ACL traffic.
Usage: python -I btsnoop.py <btsnoop_hci.log> [since_epoch_seconds]"""
import struct, sys, datetime, collections

path = sys.argv[1]
since = float(sys.argv[2]) if len(sys.argv) > 2 else 0
EPOCH_DELTA = 0x00dcddb30f2f8000  # microseconds from 0 AD to 1970

LE = {0x2006: "LE Set Adv Params", 0x2008: "LE Set Adv Data", 0x2009: "LE Set Scan Rsp Data", 0x200A: "LE Set Adv Enable",
      0x200B: "LE Set Scan Params", 0x200C: "LE Set Scan Enable", 0x200D: "LE Create Conn", 0x2036: "LE Set Ext Adv Params",
      0x2037: "LE Set Ext Adv Data", 0x2038: "LE Set Ext Scan Rsp", 0x2039: "LE Set Ext Adv Enable", 0x2041: "LE Set Ext Scan Params",
      0x2042: "LE Set Ext Scan Enable", 0x2043: "LE Ext Create Conn", 0x0405: "Create Conn (classic)", 0x0406: "Disconnect"}

with open(path, "rb") as f:
    head = f.read(16)
    assert head[:8] == b"btsnoop\0", head[:8]
    version, datalink = struct.unpack(">II", head[8:])
    print(f"btsnoop v{version}, datalink {datalink}")
    counts = collections.Counter()
    first = last = None
    while True:
        rec = f.read(24)
        if len(rec) < 24:
            break
        orig, incl, flags, drops, ts = struct.unpack(">IIIIq", rec)
        data = f.read(incl)
        t = (ts - EPOCH_DELTA) / 1e6
        first = first or t
        last = t
        if t < since or not data:
            continue
        kind = data[0]
        sent = not (flags & 1)
        when = datetime.datetime.fromtimestamp(t).strftime("%H:%M:%S.%f")[:-3]
        if kind == 0x01 and len(data) >= 4:  # HCI command
            op = data[1] | data[2] << 8
            counts["cmd"] += 1
            if op in LE:
                print(f"{when} CMD {LE[op]}: {data[4:].hex()}")
        elif kind == 0x02:  # ACL data
            counts["acl " + ("out" if sent else "in")] += 1
        elif kind == 0x04 and len(data) > 3 and data[1] == 0x3E and data[3] in (0x01, 0x0A):  # LE connection complete
            print(f"{when} EVT LE connection complete: {data[3:].hex()}")
    span = lambda x: datetime.datetime.fromtimestamp(x).strftime("%H:%M:%S") if x else "-"
    print(f"log spans {span(first)} to {span(last)}; counts {dict(counts)}")
