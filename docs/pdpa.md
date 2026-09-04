# Answering a customer's data request (M5-10)

Sri Lanka's **Personal Data Protection Act No. 9 of 2022** gives a person two rights that reach a
shop running StoreX: they may ask for a copy of what is held about them, and they may ask for it to
be destroyed. Penalties run to **Rs. 10M per instance**, which for most shops is not a fine but an
ending — so this document is written to be followed by the person standing at the counter, not by a
lawyer.

Both actions are in **Back office → Customers**, on the customer's own row, and both need a
back-office sign-in.

---

## What the shop actually holds about a person

Less than people expect, and that is worth saying out loud first.

| Held on the shop PC     | Also in the cloud |
| ----------------------- | ----------------- |
| Name                    | yes               |
| Phone number            | yes               |
| Email                   | no                |
| Note                    | no                |
| TIN and address         | no                |
| Which sales were theirs | yes               |

The cloud never receives an email address, a note, a TIN or an address. It holds a name, a number,
and the fact that certain sales belong to that person, because that is what the owner's console
needs to report.

---

## "Send me a copy of what you hold"

1. **Back office → Customers**, find the person.
2. **Export data**. A file downloads: `customer-<id>-data.json`.
3. Send them the file.

It contains their record, every sale with its **line items**, every refund, and any tax invoice
issued to them. Line items rather than a total, deliberately: the right is to the data, and what
the shop holds is that this person bought these things on these days.

The file is named after the customer's id and not their name, so a folder full of them is not
itself a pile of personal data.

**There is no deadline set in this document** — check the current regulations for the response
window before promising one.

---

## "Delete everything you have about me"

1. **Back office → Customers**, find the person.
2. **Erase…**, read the panel, then **Erase permanently**.

The name, phone number, email, note, TIN and address are destroyed on the shop PC. The blanked
record is queued for the cloud in the same instant, and overwrites what is up there the next time
the till syncs — so if a shop is offline, the erasure completes locally and finishes when the
connection returns.

**It cannot be undone.** Not by the shop, not by Lumora, not from a backup — restoring an old backup
to recover an erased customer would put back data the shop was asked to destroy. Somebody who comes
back to the shop later is a new customer.

### What stays, and why you can say so plainly

**Their sales stay, with no name on them.** The shop is required to keep its financial records, and
a sale that vanished would make the day's takings stop matching a Z-report that has already been
printed. What is left is a sale belonging to nobody in particular. That is anonymisation, which is
what the law asks for when erasure and a retention obligation meet.

**A tax invoice already issued keeps the details it was printed with.** A tax invoice shows the
purchaser's name, TIN and address because Gazette 2481/22 requires it to, and the purchaser has
filed it and claimed input credit against it. A shop cannot unilaterally revoke a statutory document
somebody else is relying on. The erasure tells you **how many** there are — for a grocery customer
it is almost always **zero**, because a tax invoice is issued on request to a VAT-registered
purchaser and not to somebody buying a loaf.

### Proving it happened

The customer's row does not disappear. Tick **include inactive** in the customer list and it is
still there, reading **Erased customer** with the date. The database also records **who** did it.
If the request is ever questioned, that is the answer.

You can still run **Export data** on an erased customer, and you should offer to: it comes back with
no name, no number and no address, which is the evidence that the shop did what it said.

---

## What this does not cover

- **Breach notification.** PDPA requires it and no software does it for you. If shop data is lost or
  stolen, that is a process the shop owner has to follow.
- **Staff.** Users have names and PINs and are not customers. Removing a member of staff is
  deactivation in **Back office → Users**, and their name stays on the shifts and sales they rang up
  — the same reasoning as a customer's sales, for the same reason.
- **Anything a shop keeps outside StoreX.** A notebook by the till is also personal data.
