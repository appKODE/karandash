use std::alloc::{alloc, dealloc, Layout};

pub fn alloc_impl(size: i32) -> i32 {
    let layout = Layout::from_size_align(size as usize, 1).unwrap();
    unsafe { alloc(layout) as i32 }
}

pub fn dealloc_impl(ptr: i32, len: i32) {
    let layout = Layout::from_size_align(len as usize, 1).unwrap();
    unsafe { dealloc(ptr as *mut u8, layout) }
}

pub fn return_string(s: &str) -> i32 {
    let bytes = s.as_bytes();
    let total = 4 + bytes.len();
    let layout = Layout::from_size_align(total, 1).unwrap();
    let ptr = unsafe { alloc(layout) };
    let len_bytes = (bytes.len() as u32).to_le_bytes();
    unsafe {
        std::ptr::copy_nonoverlapping(len_bytes.as_ptr(), ptr, 4);
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), ptr.add(4), bytes.len());
    }
    ptr as i32
}

pub fn read_host_string(ptr: i32, len: i32) -> String {
    let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, len as usize) };
    String::from_utf8_lossy(slice).to_string()
}

#[macro_export]
macro_rules! wasm_base_exports {
    ($id:expr, $name:expr, $version:expr, $mtype:expr) => {
        #[no_mangle]
        pub extern "C" fn karandash_alloc(size: i32) -> i32 {
            karandash_wasm_common::alloc_impl(size)
        }
        #[no_mangle]
        pub extern "C" fn karandash_dealloc(ptr: i32, len: i32) {
            karandash_wasm_common::dealloc_impl(ptr, len)
        }
        #[no_mangle]
        pub extern "C" fn karandash_abi_version() -> i32 {
            1
        }
        #[no_mangle]
        pub extern "C" fn karandash_module_id() -> i32 {
            karandash_wasm_common::return_string($id)
        }
        #[no_mangle]
        pub extern "C" fn karandash_module_name() -> i32 {
            karandash_wasm_common::return_string($name)
        }
        #[no_mangle]
        pub extern "C" fn karandash_module_version() -> i32 {
            karandash_wasm_common::return_string($version)
        }
        #[no_mangle]
        pub extern "C" fn karandash_module_type() -> i32 {
            karandash_wasm_common::return_string($mtype)
        }
        /// ModuleDescriptor JSON; empty `properties` means every SDK property keeps its default.
        #[no_mangle]
        pub extern "C" fn karandash_describe() -> i32 {
            karandash_wasm_common::return_string(concat!(
                "{\"id\":\"", $id, "\",\"types\":[\"", $mtype, "\"],\"properties\":{}}"
            ))
        }
        #[no_mangle]
        pub extern "C" fn karandash_initialize(_ptr: i32, _len: i32) -> i32 {
            1
        }
        #[no_mangle]
        pub extern "C" fn karandash_shutdown() -> i32 {
            1
        }
    };
}
