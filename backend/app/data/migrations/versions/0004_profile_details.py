"""profile details on the user: institution, role, research area and age

All four are optional and set by the user on the Account screen. Nothing else changes, and
existing accounts simply have them empty.

Revision ID: 0004
Revises: 0003
Create Date: 2026-11-10
"""

from alembic import op
import sqlalchemy as sa


revision = '0004'
down_revision = '0003'
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.add_column(sa.Column('institution', sa.String(length=200), nullable=True))
        batch_op.add_column(sa.Column('role', sa.String(length=40), nullable=True))
        batch_op.add_column(sa.Column('research_area', sa.String(length=200), nullable=True))
        batch_op.add_column(sa.Column('age', sa.Integer(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.drop_column('age')
        batch_op.drop_column('research_area')
        batch_op.drop_column('role')
        batch_op.drop_column('institution')
