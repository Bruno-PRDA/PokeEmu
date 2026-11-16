"""Generates core-host/src/test-rom/padtest.gba: red screen, green while A is held."""
import pathlib
import struct

rom = bytearray(4096)
def w(addr, word): struct.pack_into('<I', rom, addr, word)

w(0x00, 0xEA00002E)                 # b 0xC0
rom[0xA0:0xAC] = b'PADTEST'.ljust(12, b'\0')
rom[0xAC:0xB0] = b'ZTST'
rom[0xB0:0xB2] = b'00'
rom[0xB2] = 0x96
code = [
    0xE3A00301,  # mov r0,#0x04000000
    0xE3A01B01,  # mov r1,#0x400
    0xE3811003,  # orr r1,r1,#3        ; DISPCNT = mode 3, BG2 on
    0xE1C010B0,  # strh r1,[r0]
    0xE3A03301,  # frame: mov r3,#0x04000000
    0xE2833F4C,  # add r3,r3,#0x130    ; KEYINPUT
    0xE1D340B0,  # ldrh r4,[r3]
    0xE3140001,  # tst r4,#1           ; A (active low)
    0x03A01FF8,  # moveq r1,#0x3E0     ; A held -> green
    0x13A0101F,  # movne r1,#0x1F      ; else red
    0xE3A00406,  # mov r0,#0x06000000  ; VRAM
    0xE3A02C96,  # mov r2,#0x9600      ; 240*160 pixels
    0xE0C010B2,  # loop: strh r1,[r0],#2
    0xE2522001,  # subs r2,r2,#1
    0x1AFFFFFC,  # bne loop
    0xEAFFFFF3,  # b frame
]
for i, c in enumerate(code):
    w(0xC0 + 4 * i, c)
chk = 0
for b in rom[0xA0:0xBD]:
    chk = (chk - b) & 0xFF
rom[0xBD] = (chk - 0x19) & 0xFF

out = pathlib.Path(__file__).resolve().parent.parent / 'core-host/src/test-rom/padtest.gba'
out.write_bytes(rom)
print(f'wrote {out}')
