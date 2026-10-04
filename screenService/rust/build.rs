fn main() {
    println!("cargo:rerun-if-changed=../service.properties");
    let source = std::fs::read_to_string("../service.properties").unwrap();
    for name in ["RELAY_VERSION", "SCREEN_PORT"] {
        let value = source
            .lines()
            .find_map(|line| line.strip_prefix(&format!("{name}=")))
            .unwrap();
        let _: u16 = value.parse().expect("numeric service metadata");
        println!("cargo:rustc-env={name}={value}");
    }
}
