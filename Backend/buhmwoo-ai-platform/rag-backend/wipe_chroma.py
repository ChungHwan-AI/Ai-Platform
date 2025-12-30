# wipe_chroma.py
import os, sys, argparse
from chromadb import PersistentClient

def main():
    ap = argparse.ArgumentParser(description="Wipe Chroma collections")
    ap.add_argument("--dir", default=os.getenv("CHROMA_DIR", "./chroma_db"), help="Chroma dir")
    ap.add_argument("--collection", default=os.getenv("CHROMA_COLLECTION", "oneask_docs"),
                    help="target collection (ignored when --all given)")
    ap.add_argument("--all", action="store_true", help="delete ALL collections in this dir")
    ap.add_argument("--dry-run", action="store_true", help="show what would be deleted")
    args = ap.parse_args()

    print(f"[info] dir={args.dir}")
    client = PersistentClient(path=args.dir)
    cols = client.list_collections()
    names = [c.name for c in cols]
    print(f"[info] existing: {names}")

    if args.all:
        targets = names
    else:
        targets = [args.collection] if args.collection in names else []

    if not targets:
        print("[info] nothing to delete (no matching collections).")
        return

    print(f"[info] will delete: {targets}")
    if args.dry_run:
        print("[info] dry-run: stop.")
        return

    for name in targets:
        client.delete_collection(name)
        print(f"[info] deleted collection: {name}")

    # 확인
    names2 = [c.name for c in client.list_collections()]
    print(f"[info] now existing: {names2}")

if __name__ == "__main__":
    # 조용히 만들고 싶으면 텔레메트리 끄기
    os.environ.setdefault("CHROMA_DISABLE_TELEMETRY", "1")
    main()
