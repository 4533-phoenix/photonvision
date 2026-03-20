import socket
import struct

# Configuration
UDP_IP = "0.0.0.0"  # Listen on all available interfaces
UDP_PORT = 7001     # The port WhacknetPublisher is sending to

# Python struct format string:
# <      : Little-endian
# ddd    : 3 doubles (f64) for RobotPose (x, y, rot) -> 24 bytes
# ddd    : 3 doubles (f64) for VisionUncertainty (x, y, rot) -> 24 bytes
# Q      : 1 unsigned long long (u64) for timestamp -> 8 bytes
# B      : 1 unsigned char (u8) for camera_id -> 1 byte
# B      : 1 unsigned char (u8) for tag_count -> 1 byte
# 6x     : 6 pad bytes (ignores the reserved padding) -> 6 bytes
# Total  : 64 bytes
PACKET_FORMAT = "<ddddddQBB6x"
PACKET_SIZE = struct.calcsize(PACKET_FORMAT)

def main():
    # Initialize UDP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_IP, UDP_PORT))
    
    print(f"Listening for Whacknet UDP packets on {UDP_IP}:{UDP_PORT}...\n")

    try:
        while True:
            # Receive up to 1024 bytes
            data, addr = sock.recvfrom(1024) 
            
            # Ensure we only parse exactly 64-byte packets
            if len(data) == PACKET_SIZE:
                # Unpack the binary data
                parsed_data = struct.unpack(PACKET_FORMAT, data)
                
                # Assign to variables
                pose_x, pose_y, pose_rot = parsed_data[0:3]
                std_x, std_y, std_rot = parsed_data[3:6]
                ts_micros = parsed_data[6]
                camera_id = parsed_data[7]
                tag_count = parsed_data[8]
                
                # Print the decoded data
                print(f"--- Packet from {addr[0]}:{addr[1]} ---")
                print(f"Camera ID : {camera_id}")
                print(f"Delay     : {ts_micros} µs")
                print(f"Tag Count : {tag_count}")
                print(f"Pose      : X: {pose_x:.3f}, Y: {pose_y:.3f}, Rot: {pose_rot:.3f}")
                print(f"Std Devs  : X: {std_x:.3f}, Y: {std_y:.3f}, Rot: {std_rot:.3f}")
                print("-" * 35)
                
            else:
                print(f"Received packet of unexpected size: {len(data)} bytes from {addr}")

    except KeyboardInterrupt:
        print("\nExiting...")
    finally:
        sock.close()

if __name__ == "__main__":
    main()