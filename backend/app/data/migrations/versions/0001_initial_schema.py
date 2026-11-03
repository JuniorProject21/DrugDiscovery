"""initial schema: users, molecules, predictions, submissions (as first released)

Revision ID: 0001
Revises: 
Create Date: 2026-10-25 15:59:26.635306
"""

from alembic import op
import sqlalchemy as sa


revision = '0001'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('molecules',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('canonical_smiles', sa.Text(), nullable=False),
    sa.Column('inchikey', sa.String(length=27), nullable=True),
    sa.Column('fingerprint', sa.LargeBinary(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('canonical_smiles')
    )
    with op.batch_alter_table('molecules', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_molecules_inchikey'), ['inchikey'], unique=False)

    op.create_table('users',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('email', sa.String(length=320), nullable=False),
    sa.Column('password_hash', sa.String(length=100), nullable=False),
    sa.Column('full_name', sa.String(length=200), nullable=False),
    sa.Column('next_compound_number', sa.Integer(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_users_email'), ['email'], unique=True)

    op.create_table('predictions',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('molecule_id', sa.Integer(), nullable=False),
    sa.Column('model_version', sa.String(length=100), nullable=False),
    sa.Column('label', sa.String(length=16), nullable=False),
    sa.Column('score', sa.Float(), nullable=False),
    sa.Column('score_std', sa.Float(), nullable=True),
    sa.Column('confidence', sa.Float(), nullable=False),
    sa.Column('threshold', sa.Float(), nullable=False),
    sa.Column('p_value_active', sa.Float(), nullable=True),
    sa.Column('p_value_inactive', sa.Float(), nullable=True),
    sa.Column('nearest_similarity', sa.Float(), nullable=True),
    sa.Column('in_domain', sa.Boolean(), nullable=True),
    sa.Column('reliability', sa.String(length=8), nullable=True),
    sa.Column('pic50', sa.Float(), nullable=True),
    sa.Column('adme', sa.JSON(), nullable=True),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['molecule_id'], ['molecules.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('molecule_id', 'model_version', name='uq_prediction_molecule_model')
    )
    with op.batch_alter_table('predictions', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_predictions_molecule_id'), ['molecule_id'], unique=False)

    op.create_table('submissions',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('user_id', sa.Integer(), nullable=False),
    sa.Column('molecule_id', sa.Integer(), nullable=False),
    sa.Column('prediction_id', sa.Integer(), nullable=False),
    sa.Column('name', sa.String(length=50), nullable=False),
    sa.Column('input_smiles', sa.Text(), nullable=False),
    sa.Column('is_saved', sa.Boolean(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['molecule_id'], ['molecules.id'], ),
    sa.ForeignKeyConstraint(['prediction_id'], ['predictions.id'], ),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('submissions', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_submissions_created_at'), ['created_at'], unique=False)
        batch_op.create_index(batch_op.f('ix_submissions_molecule_id'), ['molecule_id'], unique=False)
        batch_op.create_index(batch_op.f('ix_submissions_user_id'), ['user_id'], unique=False)



def downgrade() -> None:
    with op.batch_alter_table('submissions', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_submissions_user_id'))
        batch_op.drop_index(batch_op.f('ix_submissions_molecule_id'))
        batch_op.drop_index(batch_op.f('ix_submissions_created_at'))

    op.drop_table('submissions')
    with op.batch_alter_table('predictions', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_predictions_molecule_id'))

    op.drop_table('predictions')
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_users_email'))

    op.drop_table('users')
    with op.batch_alter_table('molecules', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_molecules_inchikey'))

    op.drop_table('molecules')
