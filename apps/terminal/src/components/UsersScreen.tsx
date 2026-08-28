'use client';

import { useCallback, useEffect, useState } from 'react';

import { FIELD_CLASS, Labelled, NUMERIC_FIELD_CLASS } from '@/components/Labelled';
import type { BackOffice, Role } from '@/lib/useBackOffice';

/**
 * Staff, as the back office manages them (M3-08).
 *
 * <p>Three things this screen deliberately cannot do. It cannot delete anybody — every audit
 * column in the schema references a user, so leavers are deactivated and stay listed. It cannot
 * show a PIN, only replace one. And it cannot let the last active owner stand down, because
 * MANAGE_USERS belongs to OWNER alone and the shop would be left with nobody able to appoint a
 * replacement; the backend refuses that and this screen simply reports the refusal.
 */
interface UserRow {
  id: number;
  clientUuid: string;
  code: string;
  displayName: string;
  role: Role;
  active: boolean;
}

const ROLES: readonly Role[] = ['CASHIER', 'SUPERVISOR', 'MANAGER', 'OWNER'];

const ROLE_BLURB: Record<Role, string> = {
  CASHIER: 'Sells and runs a shift. Cannot refund.',
  SUPERVISOR: 'A cashier who may authorise refunds.',
  MANAGER: 'Everything a supervisor may do, plus the back office.',
  OWNER: 'Everything, including managing these users.',
};

export function UsersScreen({ office }: { office: BackOffice }) {
  const [users, setUsers] = useState<UserRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<UserRow | null>(null);
  const [adding, setAdding] = useState(false);

  const mayManage = office.can('MANAGE_USERS');

  const load = useCallback(async () => {
    try {
      setUsers(await office.request<UserRow[]>('/api/users'));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [office]);

  useEffect(() => {
    void load();
  }, [load]);

  const act = useCallback(
    async (what: string, run: () => Promise<unknown>) => {
      setError(null);
      setNotice(null);
      try {
        await run();
        setNotice(what);
        await load();
        return true;
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return false;
      }
    },
    [load],
  );

  return (
    <div className="flex flex-col gap-4">
      <header className="flex items-baseline justify-between gap-4">
        <div>
          <h2 className="text-ink text-lg font-semibold">Users</h2>
          <p className="text-ink-3 text-sm">
            Who may work this till, and what each of them may do.
          </p>
        </div>
        {mayManage && (
          <button
            type="button"
            onClick={() => {
              setAdding(true);
              setEditing(null);
            }}
            className="border-accent text-accent min-h-touch rounded border px-4"
          >
            Add a user
          </button>
        )}
      </header>

      {!mayManage && (
        <p role="status" className="border-pending text-pending border-l-2 px-3 py-2 text-sm">
          You can see this list but not change it — only an owner may manage users.
        </p>
      )}
      {error && (
        <p role="alert" className="border-danger text-danger border-l-2 px-3 py-2 text-sm">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="border-ok text-ok border-l-2 px-3 py-2 text-sm">
          {notice}
        </p>
      )}

      {adding && mayManage && (
        <UserForm
          title="Add a user"
          onCancel={() => setAdding(false)}
          onSubmit={async (values) => {
            const ok = await act(`${values.displayName} can now sign in.`, () =>
              office.request('/api/users', {
                method: 'POST',
                body: JSON.stringify({ clientUuid: crypto.randomUUID(), ...values }),
              }),
            );
            if (ok) setAdding(false);
          }}
        />
      )}

      {editing && mayManage && (
        <EditUser
          user={editing}
          onCancel={() => setEditing(null)}
          onRename={async (displayName, role) => {
            const ok = await act(`${displayName} updated.`, () =>
              office.request(`/api/users/${editing.id}`, {
                method: 'PUT',
                body: JSON.stringify({ displayName, role }),
              }),
            );
            if (ok) setEditing(null);
          }}
          onSetPin={async (pin) => {
            const ok = await act(`${editing.displayName}'s PIN was replaced.`, () =>
              office.request(`/api/users/${editing.id}/pin`, {
                method: 'PUT',
                body: JSON.stringify({ pin }),
              }),
            );
            if (ok) setEditing(null);
          }}
        />
      )}

      {users === null ? (
        <p className="text-ink-3 text-sm">Loading…</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {users.map((user) => (
            <li
              key={user.id}
              className={`border-hair flex flex-wrap items-center gap-x-4 gap-y-2 rounded border px-4 py-3 ${
                user.active ? '' : 'opacity-60'
              }`}
            >
              <span className="lum-money text-ink w-24 font-semibold">{user.code}</span>
              <span className="text-ink-2 flex-1">{user.displayName}</span>
              <span className="text-ink-3 w-28 text-sm">{user.role.toLowerCase()}</span>
              <span className={`w-24 text-sm ${user.active ? 'text-ok' : 'text-ink-3'}`}>
                {/* Icon plus text, never colour alone (ROADMAP §A). */}
                {user.active ? '● active' : '○ left'}
              </span>
              {mayManage && (
                <span className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => {
                      setEditing(user);
                      setAdding(false);
                    }}
                    className="border-hair text-ink-2 min-h-touch rounded border px-3"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      void act(
                        user.active
                          ? `${user.displayName} can no longer sign in.`
                          : `${user.displayName} can sign in again.`,
                        () =>
                          office.request(`/api/users/${user.id}/active`, {
                            method: 'PUT',
                            body: JSON.stringify({ active: !user.active }),
                          }),
                      )
                    }
                    className="border-hair text-ink-2 min-h-touch rounded border px-3"
                  >
                    {user.active ? 'Deactivate' : 'Reinstate'}
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------- forms

function UserForm({
  title,
  onCancel,
  onSubmit,
}: {
  title: string;
  onCancel: () => void;
  onSubmit: (values: {
    code: string;
    displayName: string;
    role: Role;
    pin: string;
  }) => Promise<void>;
}) {
  const [code, setCode] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState<Role>('CASHIER');
  const [pin, setPin] = useState('');

  return (
    <form
      className="border-accent flex flex-col gap-3 rounded border p-4"
      onSubmit={(event) => {
        event.preventDefault();
        void onSubmit({ code, displayName, role, pin });
      }}
    >
      <h3 className="text-ink font-semibold">{title}</h3>

      <div className="flex flex-wrap gap-3">
        <Labelled label="User code" hint="typed at the till — an employee number">
          <input
            value={code}
            onChange={(e) => setCode(e.target.value.toUpperCase())}
            maxLength={16}
            required
            className={NUMERIC_FIELD_CLASS}
          />
        </Labelled>
        <Labelled label="Name" hint="shown on screen and in reports">
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            required
            className={FIELD_CLASS}
          />
        </Labelled>
        <Labelled label="PIN" hint="4 digits or more, digits only">
          <input
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
            type="password"
            inputMode="numeric"
            minLength={4}
            required
            className={NUMERIC_FIELD_CLASS}
          />
        </Labelled>
      </div>

      <RolePicker role={role} onChange={setRole} />

      <div className="flex gap-2">
        <button
          type="submit"
          className="border-accent text-accent min-h-touch rounded border px-4 font-semibold"
        >
          Create
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="border-hair text-ink-2 min-h-touch rounded border px-4"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

/**
 * Renaming and re-PINning are two forms, not one.
 *
 * <p>The backend keeps them on separate endpoints so that a rename cannot reset a credential by
 * carrying a stale field, and the screen matches — a single form with a PIN box in it invites
 * exactly the confusion the split exists to prevent.
 */
function EditUser({
  user,
  onCancel,
  onRename,
  onSetPin,
}: {
  user: UserRow;
  onCancel: () => void;
  onRename: (displayName: string, role: Role) => Promise<void>;
  onSetPin: (pin: string) => Promise<void>;
}) {
  const [displayName, setDisplayName] = useState(user.displayName);
  const [role, setRole] = useState<Role>(user.role);
  const [pin, setPin] = useState('');

  return (
    <div className="border-accent flex flex-col gap-4 rounded border p-4">
      <h3 className="text-ink font-semibold">
        {user.code} — {user.displayName}
      </h3>

      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          void onRename(displayName, role);
        }}
      >
        <Labelled label="Name">
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            required
            className={FIELD_CLASS}
          />
        </Labelled>
        <RolePicker role={role} onChange={setRole} />
        <button
          type="submit"
          className="border-accent text-accent min-h-touch self-start rounded border px-4 font-semibold"
        >
          Save name and role
        </button>
      </form>

      <form
        className="border-hair flex flex-wrap items-end gap-3 border-t pt-4"
        onSubmit={(event) => {
          event.preventDefault();
          void onSetPin(pin);
        }}
      >
        <Labelled label="New PIN" hint="replaces the old one; it is never shown again">
          <input
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
            type="password"
            inputMode="numeric"
            minLength={4}
            required
            className={NUMERIC_FIELD_CLASS}
          />
        </Labelled>
        <button type="submit" className="border-hair text-ink-2 min-h-touch rounded border px-4">
          Replace PIN
        </button>
      </form>

      <button
        type="button"
        onClick={onCancel}
        className="border-hair text-ink-2 min-h-touch self-start rounded border px-4"
      >
        Done
      </button>
    </div>
  );
}

function RolePicker({ role, onChange }: { role: Role; onChange: (role: Role) => void }) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-ink-3 text-xs uppercase tracking-wider">Role</legend>
      <div className="flex flex-wrap gap-2">
        {ROLES.map((candidate) => (
          <label
            key={candidate}
            className={`min-h-touch flex cursor-pointer items-center gap-2 rounded border px-3 ${
              candidate === role ? 'border-accent text-accent' : 'border-hair text-ink-2'
            }`}
          >
            <input
              type="radio"
              name="role"
              checked={candidate === role}
              onChange={() => onChange(candidate)}
              className="sr-only"
            />
            {candidate.toLowerCase()}
          </label>
        ))}
      </div>
      <p className="text-ink-3 text-xs">{ROLE_BLURB[role]}</p>
    </fieldset>
  );
}
