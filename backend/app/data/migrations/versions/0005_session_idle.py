"""when an account was last seen, for ending an idle session

Table 6-5 asks for a session that expires after inactivity. A JWT only knows when it was
issued, so the clock has to live on the account: every request records the time here, and a
token is refused once the gap grows past INHIBITAI_SESSION_IDLE_MINUTES.

Null for accounts that have not made a request since this went in, which is read as "not idle
yet" rather than "idle forever" — nobody is signed out by the migration itself.

Revision ID: 0005
Revises: 0004
Create Date: 2026-11-20
"""

from alembic import op
import sqlalchemy as sa


revision = '0005'
down_revision = '0004'
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.add_column(sa.Column('last_seen_at', sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.drop_column('last_seen_at')
