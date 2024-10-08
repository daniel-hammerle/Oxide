use std::collections::HashMap;
use std::convert::Into;
use std::mem;
use std::str::Utf8Error;
use std::sync::Mutex;
use jni::errors::Error;
use jni::JNIEnv;
use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jbyte, jchar, jdouble, jfloat, jint, jlong, jobject, jshort};
use crate::slice_until;

pub struct Class<'a> {
    pub instance_size: usize,
    pub header_size: usize,
    pub fields: Vec<Field>,
    pub class_ptr: JClass<'a>
}

pub struct Field {
    pub tp: Type,
    pub offset: usize
}

pub enum Type {
    Byte,
    Short,
    Int,
    Long,
    Float,
    Double,
    Bool,
    Char,
    Object(String),
    Array(Box<Type>)
}

#[derive(Debug)]
pub struct FieldInfoParsingError;

impl TryFrom<&str> for Type {
    type Error = FieldInfoParsingError;
    fn try_from(value: &str) -> Result<Self, Self::Error> {
        let value = match &value[0..1] {
            "B" => Self::Byte,
            "S" => Self::Short,
            "I" => Self::Int,
            "J" => Self::Long,
            "F" => Self::Float,
            "D" => Self::Double,
            "Z" => Self::Bool,
            "C" => Self::Char,
            "L" => Self::Object(slice_until(&value[1..], ';').to_string()),
            "[" => Self::Array(Type::try_from(&value[1..])?.into()),
            _ => return Err(FieldInfoParsingError{})
        };
        Ok(value)
    }
}

impl Type {
    fn size(&self) -> usize {
        match self {
            Type::Byte => mem::size_of::<jbyte>(),
            Type::Short => mem::size_of::<jshort>(),
            Type::Int => mem::size_of::<jint>(),
            Type::Long => mem::size_of::<jlong>(),
            Type::Float => mem::size_of::<jfloat>(),
            Type::Double => mem::size_of::<jdouble>(),
            Type::Bool => mem::size_of::<jboolean>(),
            Type::Char => mem::size_of::<jchar>(), // Java chars are 2 bytes (UTF-16)
            Type::Object(_) => mem::size_of::<jobject>(), // Assuming a pointer size for objects
            Type::Array(_) => mem::size_of::<jobject>(),  // Same as Object, arrays are also pointers
        }
    }

}

#[derive(Debug)]
pub enum ConversionError {
    Native(Error),
    Utf8,
    Field(FieldInfoParsingError)
}

impl From<Error> for ConversionError {
    fn from(value: Error) -> Self {
        Self::Native(value)
    }
}

impl From<Utf8Error> for ConversionError {
    fn from(value: Utf8Error) -> Self {
        Self::Utf8
    }
}

impl From<FieldInfoParsingError> for ConversionError {
    fn from(value: FieldInfoParsingError) -> Self {
        Self::Field(value)
    }
}
impl<'a> Class<'a> {
    pub fn from_java_repr(env: &mut JNIEnv, obj: &JObject, class: JClass<'a>) -> Result<Self, ConversionError> {
        let instance_size = env.call_method(&obj, "instanceSize", "()J", &[])?.j()?;
        let header_size = env.call_method(&obj, "headerSize", "()J", &[])?.j()?;

        let fields_array = env.call_method(&obj, "fields", "()[Lat/oxide/arenas/FieldRepr;", &[])?.l()?;
        let fields_array = JObjectArray::from(fields_array);

        let fields_array_len = env.get_array_length(&fields_array)?;
        let mut fields = Vec::with_capacity(fields_array_len as usize);
        for i in 0..fields_array_len {
            let result = env.get_object_array_element(&fields_array, i)?;
            let field = Field::from_java_repr(env, &result)?;
            fields.push(field);
        }

        Ok(Self{ fields, instance_size: instance_size as usize, header_size: header_size as usize, class_ptr: class })
    }
}

impl Field {
    pub fn from_java_repr(env: &mut JNIEnv, obj: &JObject) -> Result<Self, ConversionError> {
        let offset = env.call_method(obj, "offset", "()J", &[])?.j()?;
        let name = env.call_method(obj, "name", "()Ljava/lang/String;", &[])?.l()?;
        let _ = JString::from(name);
        let tp = env.call_method(obj, "type", "()Ljava/lang/String;", &[])?.l()?;
        let tp = JString::from(tp);
        let binding = env.get_string(&tp)?;
        let tp_str = binding.to_str()?;
        Ok(Self {
            tp: tp_str.try_into()?,
            offset: offset as usize
        })
    }
}