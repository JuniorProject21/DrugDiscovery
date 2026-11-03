"""compound names, google sign-in, password reset

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-25 15:59:49.446927
"""

from alembic import op
import sqlalchemy as sa


revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('password_reset_codes',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('user_id', sa.Integer(), nullable=False),
    sa.Column('code_hash', sa.String(length=64), nullable=False),
    sa.Column('attempts', sa.Integer(), nullable=False),
    sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('used_at', sa.DateTime(timezone=True), nullable=True),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('password_reset_codes', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_password_reset_codes_user_id'), ['user_id'], unique=False)

    with op.batch_alter_table('molecules', schema=None) as batch_op:
        batch_op.add_column(sa.Column('formula', sa.String(length=100), nullable=True))
        batch_op.add_column(sa.Column('molecular_weight', sa.Float(), nullable=True))
        batch_op.add_column(sa.Column('common_name', sa.String(length=200), nullable=True))
        batch_op.add_column(sa.Column('iupac_name', sa.Text(), nullable=True))
        batch_op.add_column(sa.Column('pubchem_cid', sa.Integer(), nullable=True))
        batch_op.add_column(sa.Column('names_checked_at', sa.DateTime(timezone=True), nullable=True))

    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.add_column(sa.Column('google_sub', sa.String(length=255), nullable=True))
        batch_op.create_index('ix_users_google_sub', ['google_sub'], unique=True)



def downgrade() -> None:
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.drop_index('ix_users_google_sub')
        batch_op.drop_column('google_sub')

    with op.batch_alter_table('molecules', schema=None) as batch_op:
        batch_op.drop_column('names_checked_at')
        batch_op.drop_column('pubchem_cid')
        batch_op.drop_column('iupac_name')
        batch_op.drop_column('common_name')
        batch_op.drop_column('molecular_weight')
        batch_op.drop_column('formula')

    with op.batch_alter_table('password_reset_codes', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_password_reset_codes_user_id'))

    op.drop_table('password_reset_codes')
