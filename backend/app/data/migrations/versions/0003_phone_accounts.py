"""phone sign-up: phone columns on users, and the verification code table

An account used to be identified by its email address alone. It can now be identified by a phone
number instead, so `users.email` becomes nullable; every existing row keeps its email, and nothing
else about them changes.

Revision ID: 0003
Revises: 0002
Create Date: 2026-11-10
"""

from alembic import op
import sqlalchemy as sa


revision = '0003'
down_revision = '0002'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        'phone_verification_codes',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('user_id', sa.Integer(), nullable=False),
        sa.Column('code_hash', sa.String(length=64), nullable=False),
        sa.Column('attempts', sa.Integer(), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('used_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('id'),
    )
    with op.batch_alter_table('phone_verification_codes', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_phone_verification_codes_user_id'), ['user_id'], unique=False)

    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.add_column(sa.Column('phone', sa.String(length=20), nullable=True))
        batch_op.add_column(sa.Column('phone_verified_at', sa.DateTime(timezone=True), nullable=True))
        batch_op.alter_column('email', existing_type=sa.String(length=320), nullable=True)
        batch_op.create_index('ix_users_phone', ['phone'], unique=True)


def downgrade() -> None:
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.drop_index('ix_users_phone')
        # Accounts that have no email cannot exist once the column is required again.
        batch_op.alter_column('email', existing_type=sa.String(length=320), nullable=False)
        batch_op.drop_column('phone_verified_at')
        batch_op.drop_column('phone')

    with op.batch_alter_table('phone_verification_codes', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_phone_verification_codes_user_id'))

    op.drop_table('phone_verification_codes')
