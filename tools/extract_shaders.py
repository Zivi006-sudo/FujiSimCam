"""Rebuild the shader strings from Shaders.kt so CI can compile-check them."""
import json, re, sys
s = open('app/src/main/java/com/fujisim/cam/Shaders.kt').read()
U = re.search(r'const val UNIFORMS = """(.*?)"""', s, re.S).group(1)
C = re.search(r'const val CORE = """(.*?)"""', s, re.S).group(1)
def build(name):
    rest = s[s.index('const val ' + name + ' = ') + len('const val ' + name + ' = '):]
    out = ''
    while True:
        rest = rest.lstrip(' +\n')
        if rest.startswith('"""'):
            j = rest.index('"""', 3); out += rest[3:j]; rest = rest[j + 3:]
        elif rest.startswith('UNIFORMS'): out += U; rest = rest[8:]
        elif rest.startswith('CORE'): out += C; rest = rest[4:]
        else: break
    return out
json.dump({'agsl': build('AGSL'), 'vs': build('VERTEX_GL'), 'fs': build('FRAGMENT_GL')}, open(sys.argv[1], 'w'))
