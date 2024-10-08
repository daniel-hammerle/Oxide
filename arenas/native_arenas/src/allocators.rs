use jni::sys::jlong;
use crate::classes::Class;

pub trait Allocator {
    fn allocate(&mut self, class: Class) -> Option<&[u8]>;
}

pub struct BasicAllocator {
    allocations: Vec<Vec<u8>>
}

impl Allocator for BasicAllocator{
    fn allocate(&mut self, class: Class) -> Option<&[u8]> {
        let memory = vec![0u8; class.instance_size];
        self.allocations.push(memory);
        Some(&self.allocations.last().unwrap())
    }
}

impl Default for BasicAllocator {
    fn default() -> Self {
        Self { allocations: vec![] }
    }
}