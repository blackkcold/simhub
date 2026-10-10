#!/usr/bin/env python3
"""Generate a PHC Argon2id admin-password hash without storing the plaintext."""
import getpass
from argon2 import PasswordHasher
def main():
    p=getpass.getpass("New SIM Hub administrator password: ")
    confirm=getpass.getpass("Confirm password: ")
    if p!=confirm: raise SystemExit("Passwords do not match")
    if len(p)<12 or len(p)>1024: raise SystemExit("Require 12-1024 characters")
    print("SIMHUB_ADMIN_PASSWORD_HASH="+PasswordHasher(time_cost=3,memory_cost=65536,
        parallelism=2,hash_len=32,salt_len=16).hash(p))
if __name__=="__main__":main()
