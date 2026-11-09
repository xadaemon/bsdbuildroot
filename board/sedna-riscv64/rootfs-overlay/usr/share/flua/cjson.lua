-- SPDX-License-Identifier: BSD-2-Clause
--
-- Copyright (c) 2026 Matheus Xavier
--
-- JSON for flua, with the interface of lua-cjson, the library OC2's Lua
-- libraries are written against: cjson.encode, cjson.decode, cjson.null,
-- cjson.new() and encode_empty_table_as_object. Plain Lua, so slower than
-- the C library, which is fine for the HLAPI's small messages.
--
-- As lua-cjson does:
--   - JSON null decodes to cjson.null, which encodes back to null.
--   - A table whose keys are all positive integers is an array (holes become
--     null), other tables are objects; an empty table is an object unless
--     encode_empty_table_as_object(false) was called.
--   - Floats are written with 14 significant digits; NaN and infinities fail.
--   - Numbers without a fraction or exponent decode to Lua integers.

local string_byte, string_char, string_find, string_format, string_gsub, string_sub =
  string.byte, string.char, string.find, string.format, string.gsub, string.sub
local math_type, tostring, type, error, pairs, setmetatable, tonumber =
  math.type, tostring, type, error, pairs, setmetatable, tonumber
local table_concat = table.concat
local utf8_char = utf8.char

local null = setmetatable({}, {
  __name = "cjson.null",
  __tostring = function() return "null" end,
})

local MAX_DEPTH = 1000

local escapes = {
  ['"'] = '\\"', ["\\"] = "\\\\", ["/"] = "\\/",
  ["\b"] = "\\b", ["\f"] = "\\f", ["\n"] = "\\n", ["\r"] = "\\r", ["\t"] = "\\t",
}
for byte = 0, 31 do
  local c = string_char(byte)
  escapes[c] = escapes[c] or string_format("\\u%04x", byte)
end
escapes["\127"] = "\\u007f"

local function encode_string(s)
  return '"' .. string_gsub(s, '[%c"\\/]', escapes) .. '"'
end

local function encode_number(n)
  if math_type(n) == "integer" then
    return string_format("%d", n)
  end
  if n ~= n or n == math.huge or n == -math.huge then
    error("Cannot serialise number: must not be NaN or Infinity", 0)
  end
  return string_format("%.14g", n)
end

local function new()
  local cjson = { null = null, _NAME = "cjson", _VERSION = "2.1.0" }
  local empty_table_as_object = true

  function cjson.encode_empty_table_as_object(setting)
    if setting ~= nil then
      empty_table_as_object = setting == true or setting == "on"
    end
    return empty_table_as_object
  end

  local encode_value

  local function encode_table(t, depth, out)
    if depth > MAX_DEPTH then
      error("Cannot serialise, excessive nesting (" .. depth .. ")", 0)
    end

    local count, max, is_array = 0, 0, true
    for k in pairs(t) do
      count = count + 1
      if is_array and math_type(k) == "integer" and k > 0 then
        if k > max then max = k end
      else
        is_array = false
      end
    end

    if count == 0 then
      out[#out + 1] = empty_table_as_object and "{}" or "[]"
    elseif is_array then
      if max > count * 2 and max > 10 then
        error("Cannot serialise table: excessively sparse array", 0)
      end
      out[#out + 1] = "["
      for i = 1, max do
        if i > 1 then out[#out + 1] = "," end
        encode_value(t[i], depth + 1, out)
      end
      out[#out + 1] = "]"
    else
      out[#out + 1] = "{"
      local first = true
      for k, v in pairs(t) do
        local key
        if type(k) == "string" then
          key = k
        elseif type(k) == "number" then
          key = encode_number(k)
        else
          error("Cannot serialise " .. type(k) .. ": table key must be a number or string", 0)
        end
        if not first then out[#out + 1] = "," end
        first = false
        out[#out + 1] = encode_string(key)
        out[#out + 1] = ":"
        encode_value(v, depth + 1, out)
      end
      out[#out + 1] = "}"
    end
  end

  function encode_value(v, depth, out)
    local kind = type(v)
    if v == nil or v == null then
      out[#out + 1] = "null"
    elseif kind == "boolean" then
      out[#out + 1] = v and "true" or "false"
    elseif kind == "number" then
      out[#out + 1] = encode_number(v)
    elseif kind == "string" then
      out[#out + 1] = encode_string(v)
    elseif kind == "table" then
      encode_table(v, depth, out)
    else
      error("Cannot serialise " .. kind .. ": type not supported", 0)
    end
  end

  function cjson.encode(value)
    local out = {}
    encode_value(value, 1, out)
    return table_concat(out)
  end

  -- Decoding.

  local text

  local function fail(message, pos)
    error(string_format("Expected %s but found %s at character %d", message,
      pos > #text and "T_END" or ("'" .. string_sub(text, pos, pos) .. "'"), pos), 0)
  end

  local function skip(pos)
    return string_find(text, "[^ \t\r\n]", pos) or #text + 1
  end

  local decode_value

  local function decode_string(pos)
    -- pos is just after the opening quote.
    local parts, n = {}, 0
    while true do
      local stop = string_find(text, '["\\%c]', pos)
      if not stop then
        fail("closing quote", #text + 1)
      end
      if stop > pos then
        n = n + 1
        parts[n] = string_sub(text, pos, stop - 1)
      end
      local c = string_byte(text, stop)
      if c == 34 then -- "
        return table_concat(parts), stop + 1
      elseif c ~= 92 then -- control character
        fail("escaped control character", stop)
      end
      local e = string_sub(text, stop + 1, stop + 1)
      local plain = ({ ['"'] = '"', ["\\"] = "\\", ["/"] = "/", b = "\b", f = "\f",
                       n = "\n", r = "\r", t = "\t" })[e]
      if plain then
        n = n + 1
        parts[n] = plain
        pos = stop + 2
      elseif e == "u" then
        local hex = string_sub(text, stop + 2, stop + 5)
        local code = #hex == 4 and string_find(hex, "^%x%x%x%x$") and tonumber(hex, 16)
        if not code then
          fail("unicode escape", stop)
        end
        pos = stop + 6
        if code >= 0xD800 and code <= 0xDBFF then
          local low = string_find(text, "^\\u[dD][c-fC-F]%x%x", pos)
            and tonumber(string_sub(text, pos + 2, pos + 5), 16)
          if low then
            code = 0x10000 + (code - 0xD800) * 0x400 + (low - 0xDC00)
            pos = pos + 6
          else
            fail("low surrogate", pos)
          end
        elseif code >= 0xDC00 and code <= 0xDFFF then
          fail("high surrogate", stop)
        end
        n = n + 1
        parts[n] = utf8_char(code)
      else
        fail("escape sequence", stop)
      end
    end
  end

  local function decode_number(pos)
    local s, e = string_find(text, "^-?%d+%.?%d*[eE]?[-+]?%d*", pos)
    if not s then
      fail("value", pos)
    end
    local literal = string_sub(text, s, e)
    local value
    if string_find(literal, "^-?%d+$") then
      value = math.tointeger(tonumber(literal)) or tonumber(literal .. ".0")
    else
      value = tonumber(literal)
    end
    if value == nil then
      fail("number", pos)
    end
    return value, e + 1
  end

  function decode_value(pos, depth)
    pos = skip(pos)
    local c = string_byte(text, pos)
    if c == 123 then -- {
      if depth > MAX_DEPTH then
        error("Found too many nested data structures (" .. depth .. ") at character " .. pos, 0)
      end
      local result = {}
      pos = skip(pos + 1)
      if string_byte(text, pos) == 125 then
        return result, pos + 1
      end
      while true do
        if string_byte(text, pos) ~= 34 then
          fail("object key string", pos)
        end
        local key
        key, pos = decode_string(pos + 1)
        pos = skip(pos)
        if string_byte(text, pos) ~= 58 then
          fail("colon", pos)
        end
        result[key], pos = decode_value(pos + 1, depth + 1)
        pos = skip(pos)
        c = string_byte(text, pos)
        if c == 125 then
          return result, pos + 1
        elseif c ~= 44 then
          fail("comma or object end", pos)
        end
        pos = skip(pos + 1)
      end
    elseif c == 91 then -- [
      if depth > MAX_DEPTH then
        error("Found too many nested data structures (" .. depth .. ") at character " .. pos, 0)
      end
      local result, n = {}, 0
      pos = skip(pos + 1)
      if string_byte(text, pos) == 93 then
        return result, pos + 1
      end
      while true do
        n = n + 1
        result[n], pos = decode_value(pos, depth + 1)
        pos = skip(pos)
        c = string_byte(text, pos)
        if c == 93 then
          return result, pos + 1
        elseif c ~= 44 then
          fail("comma or array end", pos)
        end
        pos = pos + 1
      end
    elseif c == 34 then
      return decode_string(pos + 1)
    elseif c == 45 or (c and c >= 48 and c <= 57) then
      return decode_number(pos)
    elseif string_find(text, "^true", pos) then
      return true, pos + 4
    elseif string_find(text, "^false", pos) then
      return false, pos + 5
    elseif string_find(text, "^null", pos) then
      return null, pos + 4
    end
    fail("value", pos)
  end

  function cjson.decode(s)
    if type(s) ~= "string" then
      error("bad argument #1 to 'decode' (string expected, got " .. type(s) .. ")", 2)
    end
    text = s
    local ok, value, pos = pcall(decode_value, 1, 1)
    if ok then
      pos = skip(pos)
      if pos <= #text then
        ok, value = false, string_format("Expected the end but found '%s' at character %d",
          string_sub(text, pos, pos), pos)
      end
    end
    text = nil
    if not ok then
      error(value, 2)
    end
    return value
  end

  cjson.new = new
  return cjson
end

return new()
