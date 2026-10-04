import argparse
import hashlib
import json
import pathlib
import re

JAVA_KEYWORDS = set("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null record sealed permits var yield when".split())
C_KEYWORDS = set("alignas alignof and and_eq asm atomic_cancel atomic_commit atomic_noexcept auto bitand bitor bool break case catch char char8_t char16_t char32_t class compl concept const consteval constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new noexcept not not_eq nullptr operator or or_eq private protected public reflexpr register reinterpret_cast requires return short signed sizeof static static_assert static_cast struct switch synchronized template this thread_local throw true try typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq restrict".split())
PRIMITIVES = {"bool": "boolean", "u8": "int", "s8": "byte", "u16": "int", "s16": "short", "u32": "long", "s32": "int", "u64": "long", "s64": "long", "f32": "float", "f64": "double", "char": "int", "string": "java.lang.String"}
BOXED = {"boolean": "java.lang.Boolean", "byte": "java.lang.Byte", "short": "java.lang.Short", "int": "java.lang.Integer", "long": "java.lang.Long", "float": "java.lang.Float", "double": "java.lang.Double"}
C_PRIMITIVES = {"bool": "bool", "u8": "uint8_t", "s8": "int8_t", "u16": "uint16_t", "s16": "int16_t", "u32": "uint32_t", "s32": "int32_t", "u64": "uint64_t", "s64": "int64_t", "f32": "float", "f64": "double", "char": "uint32_t", "string": "plugin_string_t"}


def words(name):
    return re.findall(r"[A-Za-z0-9]+", name)


def pascal(name):
    result = "".join(part[0].upper() + part[1:] for part in words(name))
    return "Value" + result if result[0].isdigit() else result


def camel(name):
    value = pascal(name)
    value = value[0].lower() + value[1:]
    return value + "Value" if value in JAVA_KEYWORDS else value


def c_ident(name):
    value = "_".join(words(name)).lower()
    return value + "_" if value in C_KEYWORDS else value


class Generator:
    def __init__(self, schema, header, output):
        self.schema = schema
        self.types = schema["types"]
        self.interfaces = schema["interfaces"]
        self.world = next(world for world in schema["worlds"] if world["name"] == "plugin")
        self.header = re.sub(r"//[^\n]*", "", header)
        self.output = output
        self.c_types = {}
        self.functions = []
        self.exports = []
        self.resource_ids = [i for i, definition in enumerate(self.types) if definition["kind"] == "resource"]
        self.export_interfaces = {item["interface"]["id"] for item in self.world["exports"].values() if "interface" in item}
        self.prototypes = {}
        for match in re.finditer(r"^(?:extern )?([\w *]+?)\s+([a-zA-Z_]\w*)\(([^;]*?)\);", self.header, re.MULTILINE):
            result, name, parameters = match.groups()
            params = []
            if parameters.strip() != "void":
                for parameter in parameters.split(","):
                    part = re.fullmatch(r"\s*(.+?)([A-Za-z_]\w*)\s*", parameter)
                    if not part:
                        raise ValueError(parameter)
                    params.append((part[1].strip(), part[2]))
            self.prototypes[name] = (result.strip(), params)
        for index, interface in enumerate(self.interfaces):
            for function in interface["functions"].values():
                if index in self.export_interfaces:
                    self.exports.append((index, function, self.c_function(index, function, True)))
                else:
                    self.functions.append((index, function, self.c_function(index, function, False)))
        self.exports = [(None, item["function"], "exports_plugin_" + c_ident(item["function"]["name"])) for item in self.world["exports"].values() if "function" in item] + self.exports
        self.map_c_types()

    def package(self, interface):
        return "pumpkin." + "".join(words(self.interfaces[interface]["name"])).lower()

    def named_java(self, type_id):
        definition = self.types[type_id]
        owner = definition["owner"]
        if definition["name"] == "plugin-metadata" and owner and "interface" in owner and owner["interface"] in self.export_interfaces:
            return "plugin.PluginMetadata"
        if owner and "interface" in owner:
            return self.package(owner["interface"]) + "." + pascal(definition["name"])
        return "pumpkin.types." + pascal(definition["name"])

    def kind(self, type_id):
        definition = self.types[type_id]["kind"]
        return (definition, None) if isinstance(definition, str) else next(iter(definition.items()))

    def resolve(self, type_id):
        while isinstance(type_id, int) and self.kind(type_id)[0] == "type":
            type_id = self.kind(type_id)[1]
        return type_id

    def jtype(self, type_id, boxed=False):
        if type_id is None:
            return "java.lang.Void"
        if isinstance(type_id, str):
            result = PRIMITIVES[type_id]
            return BOXED.get(result, result) if boxed else result
        kind, value = self.kind(type_id)
        if kind == "type":
            return self.jtype(value, boxed)
        if kind == "handle":
            return self.jtype(next(iter(value.values())))
        if kind in ("record", "variant", "enum", "flags", "resource") or (kind == "tuple" and self.types[type_id]["name"]):
            return self.named_java(type_id)
        if kind == "list":
            if self.resolve(value) in ("u8", "s8"):
                return "byte[]"
            return f"java.util.List<{self.jtype(value, True)}>"
        if kind == "option":
            return f"pumpkin.runtime.Option<{self.jtype(value, True)}>"
        if kind == "result":
            return f"pumpkin.runtime.Result<{self.jtype(value['ok'], True)}, {self.jtype(value['err'], True)}>"
        if kind == "tuple":
            types = value["types"]
            return f"pumpkin.runtime.Tuple{len(types)}<" + ", ".join(self.jtype(item, True) for item in types) + ">"
        raise ValueError((type_id, kind))

    def c_function(self, interface, function, exported):
        prefix = "exports_" if exported else ""
        name = function["name"].replace("[", "").replace("]", "_").replace(".", "_")
        return prefix + "pumpkin_plugin_" + c_ident(self.interfaces[interface]["name"]) + "_" + c_ident(name)

    def bind_ctype(self, type_id, expression):
        if isinstance(type_id, int) and type_id not in self.c_types:
            self.c_types[type_id] = expression
            return True
        return False

    def map_c_types(self):
        for type_id, definition in enumerate(self.types):
            owner = definition["owner"]
            if not definition["name"] or not owner or self.kind(type_id)[0] == "resource":
                continue
            if "interface" in owner:
                interface = owner["interface"]
                prefix = ("exports_" if interface in self.export_interfaces else "") + "pumpkin_plugin_" + c_ident(self.interfaces[interface]["name"])
            else:
                prefix = "plugin"
            name = prefix + "_" + c_ident(definition["name"]) + "_t"
            if re.search(r"\b" + re.escape(name) + r"\b", self.header):
                self.bind_ctype(type_id, name)
        for interface, function, cname in self.functions + self.exports:
            if cname not in self.prototypes:
                raise ValueError(f"Missing C binding: {cname}")
            result, parameters = self.prototypes[cname]
            for parameter, (ctype, _) in zip(function["params"], parameters):
                self.bind_ctype(parameter["type"], ctype.removesuffix("*").strip())
            if "result" in function:
                ctype = result if result != "void" else parameters[-1][0].removesuffix("*").strip()
                self.bind_ctype(function["result"], ctype)
        changed = True
        while changed:
            changed = False
            for type_id, ctype in list(self.c_types.items()):
                kind, value = self.kind(type_id)
                child = []
                if kind == "type":
                    child.append((value, ctype))
                elif kind == "record":
                    child.extend((field["type"], f"__typeof__((({ctype}*)0)->{c_ident(field['name'])})") for field in value["fields"])
                elif kind == "tuple":
                    child.extend((item, f"__typeof__((({ctype}*)0)->f{index})") for index, item in enumerate(value["types"]))
                elif kind == "list":
                    child.append((value, f"__typeof__(*((({ctype}*)0)->ptr))"))
                elif kind == "option":
                    child.append((value, f"__typeof__((({ctype}*)0)->val)"))
                elif kind == "result":
                    child.extend((item, f"__typeof__((({ctype}*)0)->val.{tag})") for tag, item in value.items() if item is not None)
                elif kind == "variant":
                    child.extend((case["type"], f"__typeof__((({ctype}*)0)->val.{c_ident(case['name'])})") for case in value["cases"] if case.get("type") is not None)
                for item, expression in child:
                    changed = self.bind_ctype(item, expression) or changed
        missing = [i for i in range(len(self.types)) if i not in self.c_types and not self.is_resource_type(i)]
        if missing:
            raise ValueError(f"Unmapped C types: {[(i, self.types[i]['name'], self.kind(i)[0]) for i in missing]}")

    def is_resource_type(self, type_id):
        resolved = self.resolve(type_id)
        return isinstance(resolved, int) and self.kind(resolved)[0] == "resource"

    def write_file(self, relative, contents):
        path = self.output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents + "\n", encoding="utf-8")

    def emit_java(self, name, body):
        package, simple = name.rsplit(".", 1)
        self.write_file("java/" + name.replace(".", "/") + ".java", f"package {package};\n\n{body}")

    def codec_class(self, type_id):
        kind, value = self.kind(type_id)
        if kind == "resource":
            owner = self.types[type_id]["owner"]["interface"]
            return self.package(owner) + ".ResourceBindings"
        return "pumpkin.bindings.Codecs" + str(type_id // 80)

    def jread(self, type_id, reader="reader"):
        if type_id is None:
            return "null"
        if isinstance(type_id, str):
            return reader + ".read" + pascal(type_id) + "()"
        return f"{self.codec_class(type_id)}.read{type_id}({reader})"

    def jwrite(self, type_id, expression, writer="writer"):
        if isinstance(type_id, str):
            return f"{writer}.write{pascal(type_id)}({expression});"
        return f"{self.codec_class(type_id)}.write{type_id}({writer}, {expression});"

    def java_types(self):
        for type_id, definition in enumerate(self.types):
            kind, value = self.kind(type_id)
            if kind not in ("record", "variant", "enum", "flags", "resource", "tuple") or not definition["name"]:
                continue
            name = self.named_java(type_id)
            simple = name.rsplit(".", 1)[1]
            if kind in ("record", "tuple"):
                fields = value["fields"] if kind == "record" else [{"name": f"f{i}", "type": item} for i, item in enumerate(value["types"])]
                args = ", ".join(f"{self.jtype(field['type'])} {camel(field['name'])}" for field in fields)
                body = f"public record {simple}({args}) {{\n}}"
            elif kind == "enum":
                cases = ["_".join(words(case["name"])).upper() for case in value["cases"]]
                cases = ["VALUE_" + case if case[0].isdigit() else case for case in cases]
                body = f"public enum {simple} {{\n" + ",\n".join("    " + case for case in cases) + "\n}"
            elif kind == "flags":
                flags = value["flags"]
                constants = "\n".join(f"    public static final long {'_'.join(words(flag['name'])).upper()} = 1L << {i};" for i, flag in enumerate(flags))
                body = f"public record {simple}(long bits) {{\n{constants}\n\n    public {simple} {{\n        if ((bits & ~{(1 << len(flags)) - 1}L) != 0) throw new IllegalArgumentException(\"Unknown flag bits\");\n    }}\n}}"
            elif kind == "variant":
                cases = value["cases"]
                permits = ", ".join(simple + "." + pascal(case["name"]) for case in cases)
                lines = [f"public sealed interface {simple} permits {permits} {{", "    int tag();", ""]
                for index, case in enumerate(cases):
                    field = "" if case.get("type") is None else self.jtype(case["type"]) + " value"
                    lines += [f"    record {pascal(case['name'])}({field}) implements {simple} {{", "        @Override", "        public int tag() {", f"            return {index};", "        }", "    }", ""]
                body = "\n".join(lines + ["}"])
            else:
                lines = [f"public final class {simple} extends pumpkin.runtime.WitResource {{", f"    {simple}(int handle, boolean owned) {{", f"        super({type_id}, handle, owned);", "    }", ""]
                for opcode, (interface, function, cname) in enumerate(self.functions):
                    fkind = function["kind"]
                    if isinstance(fkind, dict) and next(iter(fkind.values())) == type_id:
                        lines.extend(self.java_function(opcode, function, "    "))
                body = "\n".join(lines + ["}"])
            self.emit_java(name, body)

    def java_function(self, opcode, function, indent):
        kind = function["kind"]
        method = isinstance(kind, dict) and "method" in kind
        params = function["params"][1:] if method else function["params"]
        if isinstance(kind, dict):
            name = "create" if "constructor" in kind else camel(function["name"].split(".", 1)[1])
        else:
            name = camel(function["name"])
        if method and name in ("close", "getClass", "hashCode", "toString", "clone", "finalize", "notify", "notifyAll", "wait"):
            name += "Value"
        args = ", ".join(f"{self.jtype(parameter['type'])} {camel(parameter['name'])}" for parameter in params)
        result = self.jtype(function["result"]) if "result" in function else "void"
        lines = [f"{indent}public {'static ' if not method else ''}{result} {name}({args}) {{", f"{indent}    pumpkin.runtime.Writer writer = new pumpkin.runtime.Writer();"]
        for index, parameter in enumerate(function["params"]):
            value = "this" if method and index == 0 else camel(parameter["name"])
            lines.append(indent + "    " + self.jwrite(parameter["type"], value))
        lines += ["", f"{indent}    pumpkin.runtime.Reader reader = pumpkin.runtime.Bridge.call({opcode}, writer);"]
        if "result" in function:
            lines += [f"{indent}    {result} result = {self.jread(function['result'])};", f"{indent}    reader.finish();", f"{indent}    return result;"]
        else:
            lines.append(f"{indent}    reader.finish();")
        return lines + [f"{indent}}}", ""]

    def java_functions(self):
        for index, interface in enumerate(self.interfaces):
            functions = [(opcode, function) for opcode, (owner, function, _) in enumerate(self.functions) if owner == index and function["kind"] == "freestanding"]
            if not functions:
                continue
            simple = pascal(interface["name"])
            if any(self.types[type_id]["name"] == interface["name"] and self.kind(type_id)[0] in ("resource", "record", "variant", "enum", "flags", "tuple") for type_id in interface["types"].values()):
                simple += "Api"
            lines = [f"public final class {simple} {{", f"    private {simple}() {{", "    }", ""]
            for opcode, function in functions:
                lines.extend(self.java_function(opcode, function, "    "))
            self.emit_java(self.package(index) + "." + simple, "\n".join(lines + ["}"]))
        for index, interface in enumerate(self.interfaces):
            resources = [type_id for type_id in self.resource_ids if self.types[type_id]["owner"]["interface"] == index]
            if not resources:
                continue
            lines = ["public final class ResourceBindings {", "    private ResourceBindings() {", "    }", ""]
            for type_id in resources:
                name = self.jtype(type_id)
                lines += [f"    public static {name} read{type_id}(pumpkin.runtime.Reader reader, boolean owned) {{", f"        return new {name}(reader.readS32(), owned);", "    }", ""]
            self.emit_java(self.package(index) + ".ResourceBindings", "\n".join(lines + ["}"]))

    def java_codecs(self):
        classes = {}
        for type_id in range(len(self.types)):
            kind, value = self.kind(type_id)
            if self.is_resource_type(type_id):
                continue
            name = self.jtype(type_id)
            write = []
            read = []
            if kind == "type":
                write = [self.jwrite(value, "value")]
                read = [f"return {self.jread(value)};"]
            elif kind == "handle":
                owned = "own" in value
                resource = self.resolve(next(iter(value.values())))
                write = [f"writer.writeResource(value, {str(owned).lower()});"]
                read = [f"return {self.codec_class(resource)}.read{resource}(reader, {str(owned).lower()});"]
            elif kind in ("record", "tuple"):
                fields = value["fields"] if kind == "record" else [{"name": f"f{i}", "type": item} for i, item in enumerate(value["types"])]
                write = [self.jwrite(field["type"], f"value.{camel(field['name'])}()") for field in fields]
                constructor = name.split("<", 1)[0] + ("<>" if "<" in name else "")
                read = [f"return new {constructor}(" + ", ".join(self.jread(field["type"]) for field in fields) + ");"]
            elif kind == "enum":
                write = ["writer.writeU32(value.ordinal());"]
                read = [f"return {name}.values()[reader.readTag({len(value['cases'])})];"]
            elif kind == "flags":
                write = ["writer.writeU64(value.bits());"]
                read = [f"return new {name}(reader.readU64());"]
            elif kind == "variant":
                write = ["writer.writeU32(value.tag());", "switch (value.tag()) {"]
                read = [f"return switch (reader.readTag({len(value['cases'])})) {{"]
                for index, case in enumerate(value["cases"]):
                    case_name = name + "." + pascal(case["name"])
                    write += [f"    case {index} -> {{"]
                    if case.get("type") is not None:
                        write.append("        " + self.jwrite(case["type"], f"(({case_name}) value).value()"))
                    write += ["    }"]
                    args = self.jread(case["type"]) if case.get("type") is not None else ""
                    read.append(f"    case {index} -> new {case_name}({args});")
                write += ["    default -> throw new IllegalArgumentException(\"Invalid variant tag\");", "}"]
                read += ["    default -> throw new IllegalArgumentException(\"Invalid variant tag\");", "};"]
            elif kind == "list":
                if name == "byte[]":
                    write = ["writer.writeBytes(value);"]
                    read = ["return reader.readBytes();"]
                else:
                    write = ["writer.writeU32(value.size());", f"for ({self.jtype(value, True)} item : value) {{", "    " + self.jwrite(value, "item"), "}"]
                    read = ["int size = reader.readLength();", f"java.util.ArrayList<{self.jtype(value, True)}> result = new java.util.ArrayList<>(size);", "for (int i = 0; i < size; i++) {", f"    result.add({self.jread(value)});", "}", "return java.util.Collections.unmodifiableList(result);"]
            elif kind == "option":
                write = ["writer.writeBool(value.isSome());", "if (value.isSome()) {", "    " + self.jwrite(value, "value.value()"), "}"]
                read = [f"return reader.readBool() ? pumpkin.runtime.Option.some({self.jread(value)}) : pumpkin.runtime.Option.none();"]
            elif kind == "result":
                write = ["writer.writeBool(value.isFailure());", "if (value.isFailure()) {"]
                if value["err"] is not None:
                    write.append("    " + self.jwrite(value["err"], "value.error()"))
                write.append("} else {")
                if value["ok"] is not None:
                    write.append("    " + self.jwrite(value["ok"], "value.value()"))
                write += ["}"]
                read = [f"return reader.readBool() ? pumpkin.runtime.Result.failure({self.jread(value['err'])}) : pumpkin.runtime.Result.success({self.jread(value['ok'])});"]
            else:
                raise ValueError(kind)
            cls = self.codec_class(type_id)
            lines = classes.setdefault(cls, [])
            lines += [f"    public static void write{type_id}(pumpkin.runtime.Writer writer, {name} value) {{", "        java.util.Objects.requireNonNull(value);"]
            lines += ["        " + line for line in write] + ["    }", "", f"    public static {name} read{type_id}(pumpkin.runtime.Reader reader) {{"]
            lines += ["        " + line for line in read] + ["    }", ""]
        for name, lines in classes.items():
            simple = name.rsplit(".", 1)[1]
            self.emit_java(name, f"public final class {simple} {{\n    private {simple}() {{\n    }}\n\n" + "\n".join(lines) + "}")

    def ctype(self, type_id):
        return C_PRIMITIVES[type_id] if isinstance(type_id, str) else f"pj_t{type_id}"

    def ccall(self, action, type_id, expression, buffer="buffer"):
        suffix = type_id if isinstance(type_id, int) else type_id
        if action == "free":
            return f"pj_free_{suffix}(({self.ctype(type_id)}*)&({expression}));"
        extra = ", arena" if action == "read" else ""
        const = "const " if action == "write" else ""
        return f"pj_{action}_{suffix}({buffer}, ({const}{self.ctype(type_id)}*)&({expression}){extra});"

    def c_codecs(self):
        definitions = []
        declarations = []
        aliases = [f"typedef {ctype} pj_t{type_id};" for type_id, ctype in sorted(self.c_types.items())]
        for type_id in sorted(self.c_types):
            kind, value = self.kind(type_id)
            read = []
            write = []
            free = []
            if kind == "type":
                for action, lines in [("read", read), ("write", write), ("free", free)]:
                    lines.append(self.ccall(action, value, "*value"))
            elif kind in ("record", "tuple"):
                fields = value["fields"] if kind == "record" else [{"name": f"f{i}", "type": item} for i, item in enumerate(value["types"])]
                for field in fields:
                    for action, lines in [("read", read), ("write", write), ("free", free)]:
                        lines.append(self.ccall(action, field["type"], "value->" + c_ident(field["name"])))
            elif kind == "handle":
                read = ["value->__handle = (int32_t) pj_get_u32(buffer);"]
                write = ["pj_put_u32(buffer, (uint32_t) value->__handle);"]
            elif kind in ("enum", "flags"):
                count = len(value["cases"] if kind == "enum" else value["flags"])
                if kind == "enum":
                    read = [f"uint32_t tag = pj_get_u32(buffer);", f"pj_require(tag < {count});", "*value = tag;"]
                    write = ["pj_put_u32(buffer, *value);"]
                else:
                    read = ["*value = pj_get_u64(buffer);"]
                    write = ["pj_put_u64(buffer, *value);"]
            elif kind == "list":
                read = ["value->len = pj_get_u32(buffer);", "value->ptr = pj_allocate(arena, value->len, sizeof(*value->ptr));", "for (size_t i = 0; i < value->len; i++) {", "    " + self.ccall("read", value, "value->ptr[i]"), "}"]
                write = ["pj_put_u32(buffer, value->len);", "for (size_t i = 0; i < value->len; i++) {", "    " + self.ccall("write", value, "value->ptr[i]"), "}"]
                free = ["for (size_t i = 0; i < value->len; i++) {", "    " + self.ccall("free", value, "value->ptr[i]"), "}", "if (value->len != 0) free(value->ptr);"]
            elif kind == "option":
                read = ["value->is_some = pj_get_u8(buffer);", "if (value->is_some) {", "    " + self.ccall("read", value, "value->val"), "}"]
                write = ["pj_put_u8(buffer, value->is_some);", "if (value->is_some) {", "    " + self.ccall("write", value, "value->val"), "}"]
                free = ["if (value->is_some) {", "    " + self.ccall("free", value, "value->val"), "}"]
            elif kind == "result":
                read = ["value->is_err = pj_get_u8(buffer);"]
                write = ["pj_put_u8(buffer, value->is_err);"]
                for action, lines in [("read", read), ("write", write), ("free", free)]:
                    for tag, condition in [("err", "value->is_err"), ("ok", "!value->is_err")]:
                        if value[tag] is not None:
                            lines += [f"if ({condition}) {{", "    " + self.ccall(action, value[tag], "value->val." + tag), "}"]
            elif kind == "variant":
                read = ["value->tag = pj_get_u32(buffer);"]
                write = ["pj_put_u32(buffer, value->tag);"]
                for action, lines in [("read", read), ("write", write), ("free", free)]:
                    lines.append("switch (value->tag) {")
                    for index, case in enumerate(value["cases"]):
                        lines.append(f"    case {index}:")
                        if case.get("type") is not None:
                            lines.append("        " + self.ccall(action, case["type"], "value->val." + c_ident(case["name"])))
                        lines.append("        break;")
                    lines += ["    default:", "        pj_require(false);", "}"]
            else:
                raise ValueError(kind)
            for action, lines in [("read", read), ("write", write), ("free", free)]:
                args = f"pj_t{type_id} *value" if action == "free" else f"pj_buffer *buffer, {'const ' if action == 'write' else ''}pj_t{type_id} *value" + (", pj_arena *arena" if action == "read" else "")
                signature = f"static void pj_{action}_{type_id}({args})"
                declarations.append(signature + ";")
                definitions.append(signature + " {\n" + "\n".join("    " + line for line in lines) + "\n}")
        return "\n".join(aliases + declarations + definitions)

    def c_bridge(self):
        lines = ['#include "pumpkin_runtime.h"', '#include "plugin.h"', '#include "pumpkin_wire.h"', "", self.c_codecs(), "", "void *pumpkin_java_call(int32_t opcode, const uint8_t *data, int32_t length) {", "    pj_buffer request = pj_input(data, length);", "    pj_buffer response = pj_output();", "    pj_arena storage = {0};", "    pj_arena *arena = &storage;", "    switch (opcode) {"]
        for opcode, (interface, function, cname) in enumerate(self.functions):
            result, parameters = self.prototypes[cname]
            lines += [f"        case {opcode}: {{"]
            args = []
            for index, parameter in enumerate(function["params"]):
                ctype = parameters[index][0]
                pointer = ctype.endswith("*")
                base = ctype.removesuffix("*").strip()
                variable = "arg" + str(index)
                lines += [f"            {base} {variable} = {{0}};", "            " + self.ccall("read", parameter["type"], variable, "&request")]
                args.append(("&" if pointer else "") + variable)
            lines += ["            pj_require(request.position == request.length);"]
            if "result" in function:
                ctype = result if result != "void" else parameters[-1][0].removesuffix("*").strip()
                lines.append(f"            {ctype} result = {{0}};")
                if result == "void":
                    args.append("&result")
                lines.append(f"            {'result = ' if result != 'void' else ''}{cname}({', '.join(args)});")
                lines += ["            " + self.ccall("write", function["result"], "result", "&response"), "            " + self.ccall("free", function["result"], "result")]
            else:
                lines.append(f"            {cname}({', '.join(args)});")
            lines += ["            break;", "        }"]
        lines += ["        default:", "            pj_require(false);", "    }", "    pj_arena_release(&storage);", "    return pj_finish(&response);", "}", "", "void pumpkin_java_drop(int32_t type, int32_t handle) {", "    switch (type) {"]
        for type_id in self.resource_ids:
            definition = self.types[type_id]
            interface = definition["owner"]["interface"]
            prefix = "pumpkin_plugin_" + c_ident(self.interfaces[interface]["name"])
            resource = c_ident(definition["name"])
            lines += [f"        case {type_id}: {{", f"            {prefix}_own_{resource}_t value = {{handle}};", f"            {prefix}_{resource}_drop_own(value);", "            break;", "        }"]
        lines += ["        default:", "            pj_require(false);", "    }", "}", ""]
        for opcode, (interface, function, cname) in enumerate(self.exports):
            result, parameters = self.prototypes[cname]
            signature = result + " " + cname + "(" + (", ".join(ctype + " " + name for ctype, name in parameters) or "void") + ")"
            lines += [signature + " {", "    pumpkin_java_initialize();", "    pj_buffer pj_request = pj_output();"]
            for parameter, (ctype, name) in zip(function["params"], parameters):
                expression = "*" + name if ctype.endswith("*") else name
                lines += ["    " + self.ccall("write", parameter["type"], expression, "&pj_request"), "    " + self.ccall("free", parameter["type"], expression)]
            lines += [f"    uint8_t *payload = pumpkin_java_dispatch({opcode}, pj_request.data, pj_request.length);", "    free(pj_request.data);", "    pj_buffer response = pj_result(payload);", "    pj_arena *arena = NULL;", "    pj_require(pj_get_u8(&response) == 0);"]
            if "result" in function:
                if result != "void":
                    lines.append(f"    {result} result = {{0}};")
                    target = "result"
                else:
                    target = "*" + parameters[-1][1]
                lines += ["    " + self.ccall("read", function["result"], target, "&response")]
            lines += ["    pj_require(response.position == response.length);", "    free(payload);"]
            if result != "void":
                lines.append("    return result;")
            lines += ["}", ""]
        self.write_file("native/pumpkin_bridge.c", "\n".join(lines))

    def java_exports(self):
        lines = ["public final class Exports {", "    private Exports() {", "    }", "", "    public static void dispatch(int opcode, pumpkin.runtime.Reader reader, pumpkin.runtime.Writer writer, plugin.PumpkinPlugin plugin) {", "        switch (opcode) {"]
        callbacks = []
        for opcode, (interface, function, cname) in enumerate(self.exports):
            lines += [f"            case {opcode} -> {{"]
            args = []
            for index, parameter in enumerate(function["params"]):
                variable = "arg" + str(index)
                lines.append(f"                {self.jtype(parameter['type'])} {variable} = {self.jread(parameter['type'])};")
                args.append(variable)
            lines += ["                reader.finish();"]
            name = "metadata" if interface is not None else camel(function["name"])
            target = "plugin." + name
            if name == "handleTask":
                target = "plugin.dispatchTask"
            elif name == "onLoad":
                target = "plugin.load"
            elif name == "onUnload":
                target = "plugin.unload"
            call = target + "(" + ", ".join(args) + ")"
            if "result" in function:
                lines += [f"                {self.jtype(function['result'])} result = {call};", "                " + self.jwrite(function["result"], "result")]
            else:
                lines.append("                " + call + ";")
            lines += ["            }"]
            result = self.jtype(function["result"]) if "result" in function else "void"
            params = ", ".join(f"{self.jtype(param['type'])} {camel(param['name'])}" for param in function["params"])
            callbacks.append({"name": name, "return": result, "parameters": params})
        lines += ["            default -> throw new IllegalArgumentException(\"Unknown callback: \" + opcode);", "        }", "    }", "}"]
        self.emit_java("plugin.Exports", "\n".join(lines))
        self.write_file("callbacks.json", json.dumps(callbacks, indent=2))

    def generate(self):
        self.java_types()
        self.java_functions()
        self.java_codecs()
        self.java_exports()
        self.c_bridge()
        manifest = {"interfaces": len(self.interfaces), "types": len(self.types), "resources": len(self.resource_ids), "imports": len(self.functions), "exports": len(self.exports), "schemaSha256": hashlib.sha256(json.dumps(self.schema, sort_keys=True).encode()).hexdigest(), "functions": [{"opcode": i, "interface": self.interfaces[interface]["name"], "name": function["name"], "c": cname} for i, (interface, function, cname) in enumerate(self.functions)]}
        self.write_file("manifest.json", json.dumps(manifest, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("schema", type=pathlib.Path)
    parser.add_argument("header", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()
    generator = Generator(json.loads(args.schema.read_text(encoding="utf-8")), args.header.read_text(encoding="utf-8"), args.output)
    generator.generate()


if __name__ == "__main__":
    main()
