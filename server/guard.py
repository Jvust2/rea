"""Worker-only defense against private-address fetches, including redirects.
Also deploy an egress firewall; this is defense-in-depth, not a network sandbox.
"""
import errno
import ipaddress
import socket


def public_address(value):
    ip = ipaddress.ip_address(value.split('%', 1)[0])
    return ip.is_global and not (ip.is_multicast or ip.is_reserved or ip.is_unspecified)


def install():
    original = socket.socket.connect
    original_ex = socket.socket.connect_ex
    def checked(sock, address, nonblocking=False):
        if sock.family not in (socket.AF_INET, socket.AF_INET6):
            raise OSError(errno.EACCES, 'non-IP outgoing sockets are disabled')
        host, port = address[:2]
        try:
            if not public_address(host): raise OSError(errno.EACCES, 'private destination blocked')
            target = address
        except ValueError:
            results = socket.getaddrinfo(host, port, sock.family, sock.type)
            if not results or any(not public_address(item[4][0]) for item in results):
                raise OSError(errno.EACCES, 'private DNS destination blocked')
            target = results[0][4]  # connect to the checked numeric IP, not a second DNS lookup
        return original_ex(sock, target) if nonblocking else original(sock, target)
    socket.socket.connect = lambda self, address: checked(self, address)
    socket.socket.connect_ex = lambda self, address: checked(self, address, True)
