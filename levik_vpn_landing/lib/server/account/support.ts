import "server-only";

import { writeAuditEventWithClient } from "@/lib/server/audit";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  cleanDisplayText,
  generateSupportReference,
} from "@/lib/server/account/identifiers";
import { query, withTransaction } from "@/lib/server/db";

export type SupportCategory =
  | "account"
  | "connection"
  | "subscription"
  | "privacy"
  | "other";

export type SupportStatus =
  | "open"
  | "waiting_for_support"
  | "waiting_for_user"
  | "closed";

type TicketRow = {
  ticket_id: string;
  public_reference: string;
  category: SupportCategory;
  subject: string;
  status: SupportStatus;
  created_at: Date;
  updated_at: Date;
};

type ReplyRow = {
  reply_id: string;
  ticket_id: string;
  author_type: "account" | "support" | "system";
  body: string;
  created_at: Date;
};

export type SupportTicketView = ReturnType<typeof ticketView>;

function replyView(reply: ReplyRow) {
  return {
    id: reply.reply_id,
    author: reply.author_type,
    body: reply.body,
    createdAt: reply.created_at.toISOString(),
  };
}

function ticketView(ticket: TicketRow, replies: ReplyRow[]) {
  return {
    id: ticket.ticket_id,
    reference: ticket.public_reference,
    category: ticket.category,
    subject: ticket.subject,
    status: ticket.status,
    createdAt: ticket.created_at.toISOString(),
    updatedAt: ticket.updated_at.toISOString(),
    replies: replies.map(replyView),
  };
}

function safeDiagnostics(
  value: Readonly<Record<string, string>> | undefined,
): Readonly<Record<string, string>> {
  if (!value) {
    return {};
  }
  const allowed = new Set(["appVersion", "platform", "errorCode"]);
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key, item]) => allowed.has(key) && item.length <= 120)
      .map(([key, item]) => [key, cleanDisplayText(item, 120)]),
  );
}

export async function listSupportTickets(
  accountId: string,
): Promise<SupportTicketView[]> {
  const tickets = await query<TicketRow>(
    `
      SELECT
        ticket_id,
        public_reference,
        category,
        subject,
        status,
        created_at,
        updated_at
      FROM support_tickets
      WHERE account_id = $1
      ORDER BY updated_at DESC
      LIMIT 50
    `,
    [accountId],
  );
  if (tickets.rows.length === 0) {
    return [];
  }
  const replies = await query<ReplyRow>(
    `
      SELECT
        reply.reply_id,
        reply.ticket_id,
        reply.author_type,
        reply.body,
        reply.created_at
      FROM support_ticket_replies AS reply
      WHERE reply.ticket_id = ANY($1::uuid[])
      ORDER BY reply.created_at
    `,
    [tickets.rows.map((ticket) => ticket.ticket_id)],
  );
  const repliesByTicket = new Map<string, ReplyRow[]>();
  for (const reply of replies.rows) {
    const items = repliesByTicket.get(reply.ticket_id) ?? [];
    items.push(reply);
    repliesByTicket.set(reply.ticket_id, items);
  }
  return tickets.rows.map((ticket) =>
    ticketView(ticket, repliesByTicket.get(ticket.ticket_id) ?? []),
  );
}

export async function createSupportTicket(input: {
  accountId: string;
  category: SupportCategory;
  subject: string;
  message: string;
  diagnostics?: Readonly<Record<string, string>>;
}): Promise<SupportTicketView> {
  const subject = cleanDisplayText(input.subject, 160);
  if (subject.length < 3) {
    throw new AccountApiError("invalid_request", 400);
  }
  const body = cleanDisplayText(input.message, 8_000);
  const created = await withTransaction(async (client) => {
    const ticketResult = await client.query<TicketRow>(
      `
        INSERT INTO support_tickets (
          public_reference,
          account_id,
          category,
          subject
        )
        VALUES ($1, $2, $3, $4)
        RETURNING
          ticket_id,
          public_reference,
          category,
          subject,
          status,
          created_at,
          updated_at
      `,
      [generateSupportReference(), input.accountId, input.category, subject],
    );
    const ticket = ticketResult.rows[0];
    if (!ticket) {
      throw new Error("Support ticket insert did not return a row");
    }
    const replyResult = await client.query<ReplyRow>(
      `
        INSERT INTO support_ticket_replies (
          ticket_id,
          author_type,
          body,
          diagnostic_metadata
        )
        VALUES ($1, 'account', $2, $3::jsonb)
        RETURNING reply_id, ticket_id, author_type, body, created_at
      `,
      [ticket.ticket_id, body, JSON.stringify(safeDiagnostics(input.diagnostics))],
    );
    const reply = replyResult.rows[0];
    if (!reply) {
      throw new Error("Support reply insert did not return a row");
    }
    return { ticket, reply };
  });
  return ticketView(created.ticket, [created.reply]);
}

export async function replyToSupportTicket(input: {
  accountId: string;
  ticketId: string;
  message: string;
}) {
  const body = cleanDisplayText(input.message, 8_000);
  return withTransaction(async (client) => {
    const ticket = await client.query<{ ticket_id: string }>(
      `
        SELECT ticket_id
        FROM support_tickets
        WHERE ticket_id = $1
          AND account_id = $2
          AND status <> 'closed'
        FOR UPDATE
      `,
      [input.ticketId, input.accountId],
    );
    if (ticket.rowCount !== 1) {
      throw new AccountApiError("support_ticket_not_found", 404);
    }
    const replyCount = await client.query<{ reply_count: number }>(
      `
        SELECT count(*)::integer AS reply_count
        FROM support_ticket_replies
        WHERE ticket_id = $1
      `,
      [input.ticketId],
    );
    if ((replyCount.rows[0]?.reply_count ?? 0) >= 200) {
      throw new AccountApiError("support_ticket_reply_limit", 409);
    }
    const reply = await client.query<ReplyRow>(
      `
        INSERT INTO support_ticket_replies (ticket_id, author_type, body)
        VALUES ($1, 'account', $2)
        RETURNING reply_id, ticket_id, author_type, body, created_at
      `,
      [input.ticketId, body],
    );
    await client.query(
      `
        UPDATE support_tickets
        SET status = 'waiting_for_support', updated_at = now()
        WHERE ticket_id = $1 AND account_id = $2
      `,
      [input.ticketId, input.accountId],
    );
    const row = reply.rows[0];
    if (!row) {
      throw new Error("Support reply insert did not return a row");
    }
    return replyView(row);
  });
}

export async function listSupportTicketsForStaff(
  status: SupportStatus | null,
): Promise<SupportTicketView[]> {
  const tickets = await query<TicketRow>(
    `
      SELECT
        ticket_id,
        public_reference,
        category,
        subject,
        status,
        created_at,
        updated_at
      FROM support_tickets
      WHERE ($1::text IS NULL OR status = $1)
      ORDER BY updated_at DESC
      LIMIT 100
    `,
    [status],
  );
  if (tickets.rows.length === 0) {
    return [];
  }
  const replies = await query<ReplyRow>(
    `
      SELECT reply_id, ticket_id, author_type, body, created_at
      FROM support_ticket_replies
      WHERE ticket_id = ANY($1::uuid[])
      ORDER BY created_at
    `,
    [tickets.rows.map((ticket) => ticket.ticket_id)],
  );
  const repliesByTicket = new Map<string, ReplyRow[]>();
  for (const reply of replies.rows) {
    const items = repliesByTicket.get(reply.ticket_id) ?? [];
    items.push(reply);
    repliesByTicket.set(reply.ticket_id, items);
  }
  return tickets.rows.map((ticket) =>
    ticketView(ticket, repliesByTicket.get(ticket.ticket_id) ?? []),
  );
}

export async function replyToSupportTicketAsStaff(input: {
  ticketId: string;
  message: string;
  staffUserKey: string;
}) {
  const body = cleanDisplayText(input.message, 8_000);
  return withTransaction(async (client) => {
    const ticket = await client.query<{ ticket_id: string }>(
      `
        SELECT ticket_id
        FROM support_tickets
        WHERE ticket_id = $1 AND status <> 'closed'
        FOR UPDATE
      `,
      [input.ticketId],
    );
    if (ticket.rowCount !== 1) {
      throw new AccountApiError("support_ticket_not_found", 404);
    }
    const replyCount = await client.query<{ reply_count: number }>(
      `
        SELECT count(*)::integer AS reply_count
        FROM support_ticket_replies
        WHERE ticket_id = $1
      `,
      [input.ticketId],
    );
    if ((replyCount.rows[0]?.reply_count ?? 0) >= 200) {
      throw new AccountApiError("support_ticket_reply_limit", 409);
    }
    const reply = await client.query<ReplyRow>(
      `
        INSERT INTO support_ticket_replies (ticket_id, author_type, body)
        VALUES ($1, 'support', $2)
        RETURNING reply_id, ticket_id, author_type, body, created_at
      `,
      [input.ticketId, body],
    );
    await client.query(
      `
        UPDATE support_tickets
        SET status = 'waiting_for_user', updated_at = now(), closed_at = NULL
        WHERE ticket_id = $1
      `,
      [input.ticketId],
    );
    await writeAuditEventWithClient(client, {
      eventType: "account.support.staff_reply",
      outcome: "success",
      userKey: input.staffUserKey,
      metadata: { ticketStatus: "waiting_for_user" },
    });
    const row = reply.rows[0];
    if (!row) {
      throw new Error("Support reply insert did not return a row");
    }
    return replyView(row);
  });
}

export async function setSupportTicketStatusAsStaff(
  ticketId: string,
  status: SupportStatus,
  staffUserKey: string,
): Promise<void> {
  await withTransaction(async (client) => {
    const result = await client.query(
      `
        UPDATE support_tickets
        SET
          status = $2,
          updated_at = now(),
          closed_at = CASE WHEN $2 = 'closed' THEN now() ELSE NULL END
        WHERE ticket_id = $1
      `,
      [ticketId, status],
    );
    if (result.rowCount !== 1) {
      throw new AccountApiError("support_ticket_not_found", 404);
    }
    await writeAuditEventWithClient(client, {
      eventType: "account.support.staff_status",
      outcome: "success",
      userKey: staffUserKey,
      metadata: { ticketStatus: status },
    });
  });
}
