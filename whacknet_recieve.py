import socket
import struct

# Configuration
UDP_IP = "0.0.0.0"  # Listen on all available interfaces
UDP_PORT = 7002     # The port WhacknetPublisher is sending to

# Python struct format string:
# <      : Little-endian
# dddddd : 6 doubles (f64) for RobotPose (x, y, z, roll, pitch, yaw) -> 48 bytes
# ddd    : 3 doubles (f64) for VisionUncertainty (x, y, rot) -> 24 bytes
# Q      : 1 unsigned long long (u64) for timestamp -> 8 bytes
# B      : 1 unsigned char (u8) for camera_id -> 1 byte
# B      : 1 unsigned char (u8) for tag_count -> 1 byte
# 14x    : 14 pad bytes (ignores the reserved padding) -> 14 bytes
# Total  : 96 bytes
PACKET_FORMAT = "<dddddddddQBB14x"
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
                pose_x, pose_y, pose_z, pose_roll, pose_pitch, pose_yaw = parsed_data[0:6]
                std_x, std_y, std_rot = parsed_data[6:9]
                ts_micros = parsed_data[9]
                camera_id = parsed_data[10]
                tag_count = parsed_data[11]
                
                # Print the decoded data
                print(f"--- Packet from {addr[0]}:{addr[1]} ---")
                print(f"Camera ID : {camera_id}")
                print(f"Delay     : {ts_micros} µs")
                print(f"Tag Count : {tag_count}")
                print(f"Pose      : X: {pose_x:.3f}, Y: {pose_y:.3f}, Z: {pose_z:.3f}, Roll: {pose_roll:.3f}, Pitch: {pose_pitch:.3f}, Yaw: {pose_yaw:.3f}")
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