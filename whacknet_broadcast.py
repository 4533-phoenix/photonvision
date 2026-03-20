import socket
import struct
import time

# Whacknet v2 Constants
PORT = 7002
BROADCAST_ADDR = "255.255.255.255"

# Packet Format: 
# <  = Little-Endian
# Q  = uint64 (8 bytes) for FPGA Timestamp
# d  = double (8 bytes) for Heading
# d  = double (8 bytes) for Angular Velocity
PACKET_FMT = "<Qdd"

def main():
    # Create a UDP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    
    # Enable broadcast mode
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

    print(f"Whacknet v2 Mock Publisher started.")
    print(f"Broadcasting 24-byte packets to {BROADCAST_ADDR}:{PORT} at 100Hz...")
    print("Values: Timestamp=0, Heading=0.0, Velocity=0.0")
    print("Press Ctrl+C to stop.")

    try:
        while True:
            # Pack the data into binary format
            # uint64 0, double 0.0, double 0.0
            packet = struct.pack(PACKET_FMT, 0, 0.0, 0.0)
            
            # Send the packet
            sock.sendto(packet, (BROADCAST_ADDR, PORT))
            
            # Wait 10ms (100Hz)
            time.sleep(0.01)
            
    except KeyboardInterrupt:
        print("\nStopping Mock Publisher...")
    finally:
        sock.close()

if __name__ == "__main__":
    main()