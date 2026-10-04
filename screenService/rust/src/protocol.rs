use std::io::{self, Read, Write};

pub const AUTH_SIZE: usize = 68;
pub const MAX_CONTROL: usize = 64 * 1024;
pub const MAX_FRAME: usize = 4 * 1024 * 1024 - 21;
pub const LEASE_SECONDS: u64 = 6;

pub fn read_packet(reader: &mut impl Read, min: usize, max: usize) -> io::Result<Vec<u8>> {
    let mut header = [0; 4];
    reader.read_exact(&mut header)?;
    let size = u32::from_be_bytes(header) as usize;
    if !(min..=max).contains(&size) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid packet length",
        ));
    }
    let mut data = vec![0; size];
    reader.read_exact(&mut data)?;
    Ok(data)
}

pub fn authenticated(packet: &[u8], token: &[u8; 64]) -> bool {
    if packet.len() != AUTH_SIZE || &packet[..4] != b"THA1" {
        return false;
    }
    packet[4..]
        .iter()
        .zip(token)
        .fold(0u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

#[derive(Debug)]
pub struct Control<'a> {
    pub kind: u8,
    pub x: f32,
    pub y: f32,
    pub extra: i32,
    pub text: &'a str,
}

impl<'a> Control<'a> {
    pub fn parse(data: &'a [u8]) -> Option<Self> {
        if data.len() < 17 || &data[..4] != b"THC1" {
            return None;
        }
        let x = f32::from_be_bytes(data[5..9].try_into().ok()?);
        let y = f32::from_be_bytes(data[9..13].try_into().ok()?);
        if !x.is_finite() || !y.is_finite() || data[4] > 13 {
            return None;
        }
        Some(Self {
            kind: data[4],
            x,
            y,
            extra: i32::from_be_bytes(data[13..17].try_into().ok()?),
            text: std::str::from_utf8(&data[17..]).ok()?,
        })
    }
}

#[derive(Debug)]
pub struct Feedback {
    pub keyframe: bool,
    pub pressured: bool,
    pub dropped: u32,
}

impl Feedback {
    pub fn parse(data: &[u8]) -> Option<Self> {
        if data.len() != 40 || &data[..4] != b"THF1" || data[4] != 1 {
            return None;
        }
        let read = |at| u32::from_be_bytes(data[at..at + 4].try_into().unwrap());
        let received = read(8);
        let rendered = read(12);
        let dropped = read(20);
        Some(Self {
            keyframe: data[5] & 1 != 0,
            dropped,
            pressured: dropped >= 120
                || read(24) >= 2
                || read(28) >= 80
                || u16::from_be_bytes([data[6], data[7]]) >= 3
                || (received >= 20 && u64::from(rendered) * 100 < u64::from(received) * 70),
        })
    }
}

fn nal_starts(data: &[u8], kind: u8) -> Vec<usize> {
    data.windows(4)
        .enumerate()
        .filter_map(|(i, bytes)| (bytes[..3] == [0, 0, 1] && bytes[3] & 31 == kind).then_some(i))
        .collect()
}

#[derive(Default)]
pub struct Frames {
    buffer: Vec<u8>,
}

impl Frames {
    pub fn push(&mut self, data: &[u8]) -> io::Result<Vec<Vec<u8>>> {
        self.buffer.extend_from_slice(data);
        let starts = nal_starts(&self.buffer, 9);
        let mut frames = Vec::new();
        // Preserve leading SPS/PPS/SEI, including on the first frame after restart.
        // A 4-byte start code belongs entirely to the following access unit.
        let start_code = |i: usize| {
            if i > 0 && self.buffer[i - 1] == 0 {
                i - 1
            } else {
                i
            }
        };
        let mut cursor = 0;
        for i in starts.iter().skip(1).copied() {
            let end = start_code(i);
            if end - cursor > MAX_FRAME {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "encoded frame too large",
                ));
            }
            frames.push(self.buffer[cursor..end].to_vec());
            cursor = end;
        }
        if cursor > 0 {
            self.buffer.drain(..cursor);
        }
        if self.buffer.len() > MAX_FRAME {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "encoder AUD missing or frame too large",
            ));
        }
        Ok(frames)
    }
}

pub fn write_frame(
    writer: &mut impl Write,
    frame: &[u8],
    sequence: u64,
    micros: u64,
) -> io::Result<()> {
    let size = u32::try_from(frame.len() + 21)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "frame too large"))?;
    writer.write_all(&size.to_be_bytes())?;
    writer.write_all(b"THV2")?;
    writer.write_all(&sequence.to_be_bytes())?;
    writer.write_all(&micros.to_be_bytes())?;
    writer.write_all(&[u8::from(!nal_starts(frame, 5).is_empty())])?;
    writer.write_all(frame)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_oversized_packet_before_reading_payload() {
        assert!(read_packet(&mut &u32::MAX.to_be_bytes()[..], 17, MAX_CONTROL).is_err());
        assert!(!authenticated(&[0; 68], &[b'a'; 64]));
        let mut auth = b"THA1".to_vec();
        auth.extend([b'a'; 64]);
        assert!(authenticated(&auth, &[b'a'; 64]));
    }
    #[test]
    fn aud_split_preserves_parameter_sets_and_split_start_codes() {
        let first = [0, 0, 0, 1, 0x67, 7, 0, 0, 0, 1, 9, 0xf0, 0, 0, 1, 0x65, 8];
        let next = [0, 0, 0, 1, 9, 0xf0, 0, 0, 1, 0x41, 9];
        let mut all = first.to_vec();
        all.extend(next);
        let mut frames = Frames::default();
        let mut output = Vec::new();
        for byte in all {
            output.extend(frames.push(&[byte]).unwrap());
        }
        assert_eq!(output, vec![first.to_vec()]);
        let mut wire = Vec::new();
        write_frame(&mut wire, &output[0], 7, 123).unwrap();
        assert_eq!(&wire[4..8], b"THV2");
        assert_eq!(wire[24], 1);
        assert_eq!(&wire[25..], &first);
    }
    #[test]
    fn rejects_nan_control_coordinates() {
        let mut packet = b"THC1\x00".to_vec();
        packet.extend(f32::NAN.to_be_bytes());
        packet.extend([0; 8]);
        assert!(Control::parse(&packet).is_none());
    }
}
