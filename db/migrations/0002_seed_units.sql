-- Starter unit vocabulary -- developer-curated seed data, not left for users to fill in from
-- empty (PROJECT_BRIEF.md section 4 / V1_PLAN.md Phase 2). Easy to extend later with a plain
-- follow-up migration; this is not meant to be exhaustive.
--
-- Volume factors are to_base_factor = milliliters per unit (US customary, not imperial -- see
-- PROJECT_BRIEF.md section 4's note on that ambiguity being a known v1 simplification). Mass
-- factors are grams per unit. Count-dimension units have no universal factor.

INSERT INTO units (name, abbreviation, dimension, to_base_factor, aliases) VALUES
    ('teaspoon',     'tsp',   'volume', 4.92892,  ARRAY['tsp.', 'teaspoons', 'tspn']),
    ('tablespoon',   'tbsp',  'volume', 14.7868,  ARRAY['tbsp.', 'tablespoons', 'tbsps', 'T']),
    ('fluid ounce',  'fl oz', 'volume', 29.5735,  ARRAY['fl. oz.', 'fluid ounces', 'floz']),
    ('cup',          'c',     'volume', 236.588,  ARRAY['cups', 'c.']),
    ('pint',         'pt',    'volume', 473.176,  ARRAY['pints', 'pt.']),
    ('quart',        'qt',    'volume', 946.353,  ARRAY['quarts', 'qt.']),
    ('gallon',       'gal',   'volume', 3785.41,  ARRAY['gallons', 'gal.']),
    ('milliliter',   'ml',    'volume', 1,        ARRAY['milliliters', 'millilitre', 'millilitres', 'mL']),
    ('liter',        'l',     'volume', 1000,     ARRAY['liters', 'litre', 'litres', 'L']),

    ('gram',         'g',     'mass',   1,        ARRAY['grams', 'gr']),
    ('kilogram',     'kg',    'mass',   1000,     ARRAY['kilograms', 'kilo', 'kilos']),
    ('ounce',        'oz',    'mass',   28.3495,  ARRAY['ounces', 'oz.']),
    ('pound',        'lb',    'mass',   453.592,  ARRAY['pounds', 'lbs', 'lb.']),

    ('clove',        NULL,    'count',  NULL,     ARRAY['cloves']),
    ('can',          NULL,    'count',  NULL,     ARRAY['cans']),
    ('bunch',        NULL,    'count',  NULL,     ARRAY['bunches']),
    ('package',      'pkg',   'count',  NULL,     ARRAY['packages', 'pack', 'packs']),
    ('slice',        NULL,    'count',  NULL,     ARRAY['slices']),
    ('pinch',        NULL,    'count',  NULL,     ARRAY['pinches']),
    ('dash',         NULL,    'count',  NULL,     ARRAY['dashes']),
    ('stick',        NULL,    'count',  NULL,     ARRAY['sticks']);
