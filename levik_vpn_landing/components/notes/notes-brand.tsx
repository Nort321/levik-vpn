import Image from "next/image";
import Link from "next/link";

export function NotesBrand() {
  return (
    <Link aria-label="Levik Notes — создать заметку" className="notes-brand" href="/">
      <Image alt="" height={48} priority src="/assets/levik-shield.png" width={48} />
      <span>
        <strong>Levik</strong>
        <b>Notes</b>
      </span>
    </Link>
  );
}
