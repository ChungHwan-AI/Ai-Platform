import os, argparse, re, sys
from chromadb import PersistentClient
from config_chroma import CHROMA_DIR, CHROMA_COLLECTION

def iter_docs(col, batch=500, include=("metadatas","documents")):
    off = 0
    while True:
        g = col.get(limit=batch, offset=off, include=list(include))
        ids = g.get("ids", [])
        if not ids:
            break
        metas = g.get("metadatas") or []
        docs = g.get("documents") or []
        for i, did in enumerate(ids):
            yield did, (metas[i] or {}), docs[i] if i < len(docs) else None
        off += len(ids)

def list_top_values(col, key: str, top: int = 20):
    from collections import Counter
    c = Counter()
    for _, meta, _ in iter_docs(col, include=("metadatas",)):
        if key in meta and meta[key] is not None:
            c[str(meta[key])] += 1
    return c.most_common(top)

def main():
    ap = argparse.ArgumentParser(description="Delete Chroma items by metadata")
    ap.add_argument("--key", default="source", help="metadata key (default: source)")
    group = ap.add_mutually_exclusive_group()
    group.add_argument("--eq", help="exact match value (==)")
    group.add_argument("--contains", help="substring match (case-insensitive)")
    group.add_argument("--regex", help="regex pattern (Python re)")
    ap.add_argument("--list", action="store_true", help="list top values for --key and exit")
    ap.add_argument("--dry-run", action="store_true", help="do not delete, just show what would be deleted")
    ap.add_argument("--limit-preview", type=int, default=5, help="show first N matches")
    ap.add_argument("--batch-delete", type=int, default=1000, help="delete in chunks")
    args = ap.parse_args()

    cli = PersistentClient(path=CHROMA_DIR)
    col = cli.get_or_create_collection(CHROMA_COLLECTION)

    print(f"[info] dir={CHROMA_DIR} collection={CHROMA_COLLECTION} total={col.count()} key={args.key}")

    if args.list:
        top = list_top_values(col, key=args.key, top=50)
        print("[info] Top values:")
        for v, cnt in top:
            print(f" - {v} : {cnt}")
        return

    # build matcher
    def match(meta_val: str | None) -> bool:
        if meta_val is None:
            return False
        s = str(meta_val)
        if args.eq is not None:
            return s == args.eq
        if args.contains is not None:
            return args.contains.lower() in s.lower()
        if args.regex is not None:
            return re.search(args.regex, s) is not None
        # no filter given
        print("[ERR] No filter specified. Use --eq or --contains or --regex, or --list.", file=sys.stderr)
        sys.exit(2)

    # scan & collect ids
    matches = []
    previews = 0
    for did, meta, doc in iter_docs(col):
        val = meta.get(args.key)
        if match(val):
            matches.append(did)
            if previews < args.limit_preview:
                snippet = (doc[:120] + "…") if isinstance(doc, str) and len(doc or "") > 120 else (doc or "")
                print(f"[match] id={did} {args.key}={val} | snippet={snippet}")
                previews += 1

    print(f"[info] matched={len(matches)}")

    if args.dry_run:
        print("[info] dry-run: nothing deleted")
        return

    if not matches:
        print("[info] nothing to delete")
        return

    # delete in chunks
    from math import ceil
    n = len(matches); b = args.batch_delete
    for i in range(0, n, b):
        chunk = matches[i:i+b]
        col.delete(ids=chunk)
        print(f"[info] deleted {i+len(chunk)}/{n}")

    print("[info] done. total now:", col.count())

if __name__ == "__main__":
    main()
